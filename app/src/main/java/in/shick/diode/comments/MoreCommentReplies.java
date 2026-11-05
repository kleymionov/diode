package in.shick.diode.comments;

import in.shick.diode.things.Listing;
import in.shick.diode.things.ListingData;
import in.shick.diode.things.ThingInfo;
import in.shick.diode.things.ThingListing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merges overlapping per-comment listings back into one ordered reply tree. */
public final class MoreCommentReplies {
    public static final int REQUESTS_PER_LOAD = 20;
    private final LinkedHashMap<String, ThingListing> entries = new LinkedHashMap<String, ThingListing>();

    public static List<String> childIds(ThingInfo more) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (more.getChildren() != null) {
            for (String id : more.getChildren()) if (validId(id)) result.add(id);
        } else if (validId(more.getId())) {
            result.add(more.getId());
        }
        return new ArrayList<String>(result);
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9]+");
    }

    public static String commentUrl(String threadId, String childId, String sort) throws IOException {
        if (!validId(threadId) || !validId(childId)) throw new IOException("Invalid comment ID");
        // Fetch each omitted subtree without including already-visible ancestors.
        return "https://www.reddit.com/comments/" + threadId + "/z/" + childId
                + "/.json?" + sort + "&context=0";
    }

    public static ThingListing[] comments(Listing[] response) throws IOException {
        if (response == null || response.length < 2 || response[1] == null
                || !"Listing".equals(response[1].getKind()) || response[1].getData() == null
                || response[1].getData().getChildren() == null) {
            throw new IOException("Invalid comment listing");
        }
        return response[1].getData().getChildren();
    }

    public boolean containsComment(String id) { return entries.containsKey("t1_" + id); }

    public void add(ThingListing thing) throws IOException {
        if (thing == null || thing.getData() == null) throw new IOException("Invalid comment entry");
        ThingInfo item = thing.getData();
        String key;
        if ("t1".equals(thing.getKind())) {
            if (!validId(item.getId())) throw new IOException("Missing comment ID");
            key = "t1_" + item.getId();
        } else if ("more".equals(thing.getKind())) {
            key = ("_".equals(item.getId()) ? "continue:" : "more:") + item.getParent_id();
        } else {
            return;
        }
        ThingListing previous = entries.get(key);
        if (previous == null) {
            entries.put(key, thing);
        } else if ("more".equals(thing.getKind())) {
            LinkedHashSet<String> combined = new LinkedHashSet<String>(childIds(previous.getData()));
            combined.addAll(childIds(item));
            previous.getData().setChildren(new ArrayList<String>(combined));
        }
        Listing replies = item.getReplies();
        if (replies != null && replies.getData() != null && replies.getData().getChildren() != null) {
            for (ThingListing child : replies.getData().getChildren()) add(child);
        }
    }

    public void addRemaining(ThingInfo original, List<String> ids) throws IOException {
        if (ids.isEmpty()) return;
        ThingInfo data = new ThingInfo();
        data.setId(ids.get(0));
        data.setParent_id(original.getParent_id());
        data.setChildren(new ArrayList<String>(ids));
        ThingListing thing = new ThingListing();
        thing.setKind("more");
        thing.setData(data);
        add(thing);
    }

    public List<ThingListing> build(Set<String> attempted, Set<String> existing) throws IOException {
        LinkedHashMap<String, ThingListing> included = new LinkedHashMap<String, ThingListing>();
        for (Map.Entry<String, ThingListing> entry : entries.entrySet()) {
            ThingListing thing = entry.getValue();
            ThingInfo item = thing.getData();
            if ("t1".equals(thing.getKind())) {
                if (existing.contains(entry.getKey())) continue;
            } else if (!"_".equals(item.getId())) {
                ArrayList<String> remaining = new ArrayList<String>();
                for (String id : childIds(item)) {
                    if (!attempted.contains(id) && !containsComment(id) && !existing.contains("t1_" + id)) {
                        remaining.add(id);
                    }
                }
                if (remaining.isEmpty()) continue;
                item.setChildren(remaining);
                item.setId(remaining.get(0));
            }
            item.setReplies(null);
            included.put(entry.getKey(), thing);
        }

        // Reject parent cycles before constructing a recursively-rendered tree.
        for (Map.Entry<String, ThingListing> entry : included.entrySet()) {
            HashSet<String> path = new HashSet<String>();
            String cursor = entry.getKey();
            while (included.containsKey(cursor)) {
                if (!path.add(cursor)) throw new IOException("Invalid cyclic comment tree");
                cursor = included.get(cursor).getData().getParent_id();
            }
        }

        ArrayList<ThingListing> roots = new ArrayList<ThingListing>();
        LinkedHashMap<String, List<ThingListing>> children = new LinkedHashMap<String, List<ThingListing>>();
        for (ThingListing thing : included.values()) {
            String parentId = thing.getData().getParent_id();
            ThingListing parent = included.get(parentId);
            if (parent != null && "t1".equals(parent.getKind())) {
                List<ThingListing> siblings = children.get(parentId);
                if (siblings == null) {
                    siblings = new ArrayList<ThingListing>();
                    children.put(parentId, siblings);
                }
                siblings.add(thing);
            } else {
                roots.add(thing);
            }
        }
        for (Map.Entry<String, List<ThingListing>> entry : children.entrySet()) {
            ListingData data = new ListingData();
            data.setChildren(entry.getValue().toArray(new ThingListing[0]));
            Listing listing = new Listing();
            listing.setKind("Listing");
            listing.setData(data);
            included.get(entry.getKey()).getData().setReplies(listing);
        }
        return roots;
    }
}
