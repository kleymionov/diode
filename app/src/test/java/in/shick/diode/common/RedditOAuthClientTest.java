package in.shick.diode.common;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class RedditOAuthClientTest {
    private static final String WORLDNEWS = "https://www.reddit.com/r/worldnews/hot/.json?after=t3_test&count=25";
    private static final String TOKEN = "{\"access_token\":\"test-token\",\"expires_in\":3600}";

    private static class Fixture implements RedditOAuthClient.Transport, RedditOAuthClient.Clock {
        final ArrayDeque<RedditOAuthClient.Response> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        long now = 1000;
        final RedditOAuthClient client = new RedditOAuthClient("my-client", "my_username",
                "c2c8dc99-18b3-4337-93be-a7fa63ad52de", this, this);

        public long now() { return now; }

        public RedditOAuthClient.Response send(String method, String url, Map<String, String> values, byte[] body)
                throws IOException {
            urls.add(method + " " + url);
            headers.add(new HashMap<>(values));
            bodies.add(body == null ? "" : new String(body, "UTF-8"));
            if (responses.isEmpty()) throw new AssertionError("Unexpected request: " + url);
            return responses.remove();
        }

        void reply(int status, String body) throws Exception {
            responses.add(new RedditOAuthClient.Response(status,
                    Collections.singletonMap("Content-Type", "application/json"), body.getBytes("UTF-8")));
        }
    }

    @Test public void authenticatesAndLoadsWorldnewsWithPagination() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN);
        f.reply(200, "{\"kind\":\"Listing\"}");
        f.client.read(WORLDNEWS);
        assertEquals("POST https://www.reddit.com/api/v1/access_token", f.urls.get(0));
        assertTrue(f.bodies.get(0).contains("grant_type=https%3A%2F%2Foauth.reddit.com%2Fgrants%2Finstalled_client"));
        assertTrue(f.bodies.get(0).contains("device_id=c2c8dc99-18b3-4337-93be-a7fa63ad52de"));
        assertEquals("GET https://oauth.reddit.com/r/worldnews/hot/.json?after=t3_test&count=25", f.urls.get(1));
        assertEquals("Bearer test-token", f.headers.get(1).get("Authorization"));
        assertTrue(f.headers.get(1).get("User-Agent").contains("in.shick.diode.personal"));
        assertTrue(f.headers.get(1).get("User-Agent").contains("/u/my_username"));
    }

    @Test public void reusesTokenThenRenewsBeforeExpiry() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN); f.reply(200, "{}"); f.reply(200, "[]");
        f.client.read(WORLDNEWS); f.client.read("https://www.reddit.com/comments/test/.json");
        assertEquals(3, f.urls.size());
        f.now += 3540001;
        f.reply(200, TOKEN); f.reply(200, "{}");
        f.client.read(WORLDNEWS);
        assertEquals(5, f.urls.size());
        assertTrue(f.urls.get(3).startsWith("POST "));
    }

    @Test public void retriesUnauthorizedOnceWithNewToken() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN); f.reply(401, ""); f.reply(200, TOKEN); f.reply(401, "");
        try { f.client.read(WORLDNEWS); fail(); }
        catch (RedditOAuthClient.ApiException e) { assertTrue(e.getMessage().contains("authorization failed")); }
        assertEquals(4, f.urls.size());
    }

    @Test public void rejectsHtmlWithoutPassingItToListingParser() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN);
        f.responses.add(new RedditOAuthClient.Response(200,
                Collections.singletonMap("Content-Type", "text/html"), "<html>blocked</html>".getBytes("UTF-8")));
        try { f.client.read(WORLDNEWS); fail(); }
        catch (RedditOAuthClient.ApiException e) { assertTrue(e.getMessage().contains("web page")); }
    }

    @Test public void forbiddenExplainsApiAccessWithoutRetrying() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN); f.reply(403, "");
        try { f.client.read(WORLDNEWS); fail(); }
        catch (RedditOAuthClient.ApiException e) { assertTrue(e.getMessage().contains("403")); }
        assertEquals(2, f.urls.size());
    }

    @Test public void rateLimitPreventsRequestsUntilRetryAfter() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN);
        f.responses.add(new RedditOAuthClient.Response(429,
                Collections.singletonMap("Retry-After", "120"), new byte[0]));
        try { f.client.read(WORLDNEWS); fail(); } catch (RedditOAuthClient.ApiException expected) { }
        try { f.client.read(WORLDNEWS); fail(); }
        catch (RedditOAuthClient.ApiException e) { assertTrue(e.getMessage().contains("seconds")); }
        assertEquals(2, f.urls.size());
        f.now += 120001;
        f.reply(200, "{}");
        f.client.read(WORLDNEWS);
        assertEquals(3, f.urls.size());
    }

    @Test public void respectsExhaustedQuotaOnSuccessfulResponse() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, TOKEN);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Ratelimit-Remaining", "0.0");
        headers.put("X-Ratelimit-Reset", "30");
        f.responses.add(new RedditOAuthClient.Response(200, headers, "{}".getBytes("UTF-8")));
        f.client.read(WORLDNEWS);
        try { f.client.read(WORLDNEWS); fail(); } catch (RedditOAuthClient.ApiException expected) { }
        assertEquals(2, f.urls.size());
    }

    @Test public void neverSendsCredentialsToOtherHostsOrCleartext() throws Exception {
        Fixture f = new Fixture();
        for (String url : new String[] {"https://evil.example/r/worldnews/.json",
                "http://www.reddit.com/.json", "https://oauth.reddit.com.evil.example/.json",
                "https://user@oauth.reddit.com/.json", "https://oauth.reddit.com:443/.json"}) {
            try { f.client.read(url); fail(url); } catch (RedditOAuthClient.ApiException expected) { }
        }
        assertEquals(0, f.urls.size());
    }

    @Test public void missingConfigurationDoesNotSendRequests() throws Exception {
        Fixture f = new Fixture();
        RedditOAuthClient client = new RedditOAuthClient("", "", "device", f, f);
        try { client.read(WORLDNEWS); fail(); }
        catch (RedditOAuthClient.ApiException e) { assertTrue(e.getMessage().contains("Settings")); }
        assertEquals(0, f.urls.size());
    }

    @Test public void optionalContactDoesNotInventUsernameOrImpersonateAnotherApp() throws Exception {
        Fixture f = new Fixture();
        RedditOAuthClient client = new RedditOAuthClient("my-client", "", "device", f, f);
        f.reply(200, TOKEN); f.reply(200, "{}");
        client.read(WORLDNEWS);
        assertEquals("android:in.shick.diode.personal:1.3.2.3", f.headers.get(0).get("User-Agent"));
        assertEquals("android:in.shick.diode.personal:1.3.2.3", f.headers.get(1).get("User-Agent"));
    }

    @Test public void tokenErrorDoesNotLeakBodyOrCredentials() throws Exception {
        Fixture f = new Fixture();
        f.reply(200, "{\"error\":\"invalid_client\",\"details\":\"private-value\"}");
        try { f.client.read(WORLDNEWS); fail(); }
        catch (RedditOAuthClient.ApiException e) {
            assertTrue(e.getMessage().contains("API approval"));
            assertFalse(e.getMessage().contains("private-value"));
        }
        assertEquals(1, f.urls.size());
    }
}
