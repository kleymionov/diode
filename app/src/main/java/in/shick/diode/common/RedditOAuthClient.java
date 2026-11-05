package in.shick.diode.common;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Read-only, application-only OAuth for a configured installed-app client ID. */
public final class RedditOAuthClient {
    public interface Transport {
        Response send(String method, String url, Map<String, String> headers, byte[] body)
                throws IOException;
    }

    public interface Clock { long now(); }

    public static final class ApiException extends IOException {
        public ApiException(String message) { super(message); }
    }

    public static final class Response {
        public final int status;
        public final Map<String, String> headers;
        public final byte[] body;

        public Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
            }
            return "";
        }
    }

    private final String clientId;
    private final String deviceId;
    private final String userAgent;
    private final String username;
    private final Transport transport;
    private final Clock clock;
    private String token;
    private long expiresAt;
    private long retryAt;

    public RedditOAuthClient(String clientId, String username, String deviceId,
                             Transport transport, Clock clock) {
        this.clientId = clientId;
        this.username = username;
        this.deviceId = deviceId;
        this.transport = transport;
        this.clock = clock;
        this.userAgent = "android:in.shick.diode.personal:1.3.2.3"
                + (username.isEmpty() ? "" : " (by /u/" + username + ")");
    }

    public static boolean validClientId(String value) {
        return value.matches("[A-Za-z0-9_-]{5,128}");
    }

    public static boolean validUsername(String value) {
        return value.matches("[A-Za-z0-9_-]{3,32}");
    }

    public static String apiUrl(String original) throws ApiException {
        try {
            URI uri = new URI(original);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getFragment() != null
                    || !("www.reddit.com".equals(host) || "reddit.com".equals(host)
                    || "np.reddit.com".equals(host) || "oauth.reddit.com".equals(host))) {
                throw new IllegalArgumentException();
            }
            return "https://oauth.reddit.com" + uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (Exception e) {
            throw new ApiException("Invalid Reddit API address.");
        }
    }

    public synchronized Response read(String originalUrl) throws IOException {
        String url = apiUrl(originalUrl);
        if (!validClientId(clientId) || (!username.isEmpty() && !validUsername(username))) {
            throw new ApiException("Set a Reddit client ID in Settings > Reddit API.");
        }
        if (clock.now() < retryAt) {
            throw new ApiException("Reddit rate limit reached. Try again in "
                    + ((retryAt - clock.now()) / 1000 + 1) + " seconds.");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureToken();
            Map<String, String> headers = headers();
            headers.put("Authorization", "Bearer " + token);
            Response response = transport.send("GET", url, headers, null);
            if (response.status == 401) {
                token = null;
                if (attempt == 0) continue;
            }
            checkResponse(response);
            if ("0".equals(response.header("X-Ratelimit-Remaining"))
                    || "0.0".equals(response.header("X-Ratelimit-Remaining"))) {
                retryAt = clock.now() + seconds(response.header("X-Ratelimit-Reset"), 60) * 1000;
            }
            return response;
        }
        throw new ApiException("Reddit authorization failed. Check your own app's API access.");
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "application/json");
        return headers;
    }

    private void ensureToken() throws IOException {
        if (token != null && clock.now() < expiresAt) return;
        token = null;
        Map<String, String> headers = headers();
        headers.put("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((clientId + ":").getBytes("UTF-8")));
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("grant_type=" + URLEncoder.encode(
                "https://oauth.reddit.com/grants/installed_client", "UTF-8")
                + "&device_id=" + URLEncoder.encode(deviceId, "UTF-8")).getBytes("UTF-8");
        Response response = transport.send("POST", "https://www.reddit.com/api/v1/access_token",
                headers, body);
        checkResponse(response);
        JsonNode json;
        try {
            json = new ObjectMapper().readTree(new java.io.ByteArrayInputStream(response.body));
        } catch (IOException e) {
            throw new ApiException("Reddit returned an invalid OAuth response.");
        }
        if (json == null || !json.isObject()) {
            throw new ApiException("Reddit returned an invalid OAuth response.");
        }
        JsonNode accessToken = json.get("access_token");
        JsonNode lifetime = json.get("expires_in");
        if (accessToken == null || !accessToken.isTextual() || accessToken.getTextValue().isEmpty()
                || lifetime == null || !lifetime.isNumber() || lifetime.getLongValue() <= 0
                || json.has("error")) {
            throw new ApiException("Reddit did not authorize this client ID. Check your app's API approval.");
        }
        token = accessToken.getTextValue();
        expiresAt = clock.now() + Math.max(1, Math.min(86400, lifetime.getLongValue()) - 60) * 1000;
    }

    private void checkResponse(Response response) throws ApiException {
        if (response.status == 429) {
            retryAt = clock.now() + seconds(response.header("Retry-After"), 60) * 1000;
            throw new ApiException("Reddit rate limit reached. Wait before refreshing.");
        }
        if (response.status == 401) {
            throw new ApiException("Reddit authorization failed. Check your own app's client ID and approval.");
        }
        if (response.status == 403) {
            throw new ApiException("Reddit denied access (403). Your app needs approved API access; some communities restrict access too.");
        }
        if (response.status == 404) throw new ApiException("Reddit could not find this community or post (404).");
        if (response.status < 200 || response.status >= 300) {
            throw new ApiException("Reddit request failed (HTTP " + response.status + "). Try again later.");
        }
        String contentType = response.header("Content-Type").toLowerCase(java.util.Locale.US);
        String start;
        try {
            start = new String(response.body, "UTF-8").trim();
        } catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
        if (!contentType.contains("application/json") || !(start.startsWith("{") || start.startsWith("["))) {
            throw new ApiException("Reddit returned a web page instead of API data. Check your app's API access.");
        }
    }

    private static long seconds(String value, long fallback) {
        try { return Math.max(1, Math.min(86400, Long.parseLong(value))); }
        catch (NumberFormatException e) { return fallback; }
    }

    /** No redirects or shared browser cookies; bearer credentials stay on the API host. */
    public static final class NetworkTransport implements Transport {
        public Response send(String method, String url, Map<String, String> headers, byte[] body)
                throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setRequestMethod(method);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            try {
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length);
                    OutputStream output = connection.getOutputStream();
                    try { output.write(body); } finally { output.close(); }
                }
                int status = connection.getResponseCode();
                Map<String, String> responseHeaders = new HashMap<String, String>();
                for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                        responseHeaders.put(entry.getKey(), entry.getValue().get(0));
                    }
                }
                // Error bodies can contain HTML and are never needed or logged.
                if (status < 200 || status >= 300) return new Response(status, responseHeaders, new byte[0]);
                InputStream input = connection.getInputStream();
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 8 * 1024 * 1024) {
                            throw new ApiException("Reddit response is too large. Try loading fewer comments.");
                        }
                        output.write(buffer, 0, count);
                    }
                    return new Response(status, responseHeaders, output.toByteArray());
                } finally { input.close(); }
            } finally { connection.disconnect(); }
        }
    }
}
