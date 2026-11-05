package in.shick.diode.comments;

import in.shick.diode.things.Listing;
import in.shick.diode.things.ListingData;
import in.shick.diode.things.ThingInfo;
import in.shick.diode.things.ThingListing;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class MoreCommentRepliesTest {
    private static Set<String> ids(String... values) { return new HashSet<String>(Arrays.asList(values)); }

    private static ThingListing comment(String id, String parent, ThingListing... replies) {
        ThingInfo data = new ThingInfo();
        data.setId(id); data.setName("t1_" + id); data.setParent_id(parent);
        if (replies.length > 0) {
            ListingData children = new ListingData(); children.setChildren(replies);
            Listing listing = new Listing(); listing.setKind("Listing"); listing.setData(children);
            data.setReplies(listing);
        }
        ThingListing thing = new ThingListing(); thing.setKind("t1"); thing.setData(data);
        return thing;
    }

    private static ThingListing more(String parent, String... children) {
        ThingInfo data = new ThingInfo(); data.setId(children.length == 0 ? "_" : children[0]);
        data.setParent_id(parent); data.setChildren(new ArrayList<String>(Arrays.asList(children)));
        ThingListing thing = new ThingListing(); thing.setKind("more"); thing.setData(data);
        return thing;
    }

    @Test public void readsAllChildIdsFromRedditJsonInsteadOfOnlyTheStubId() throws Exception {
        ThingListing parsed = new ObjectMapper().readValue(
                "{\"kind\":\"more\",\"data\":{\"id\":\"a\",\"children\":[\"a\",\"b\",\"c\"]}}", ThingListing.class);
        assertEquals(Arrays.asList("a", "b", "c"), MoreCommentReplies.childIds(parsed.getData()));
    }

    @Test public void removesDuplicateAndInvalidIdsAndSupportsLegacyStubs() {
        assertEquals(Arrays.asList("a", "b"), MoreCommentReplies.childIds(more("t1_p", "a", "a", "b", "../bad").getData()));
        ThingInfo legacy = new ThingInfo(); legacy.setId("old123");
        assertEquals(Collections.singletonList("old123"), MoreCommentReplies.childIds(legacy));
        assertTrue(MoreCommentReplies.childIds(more("t1_p").getData()).isEmpty());
    }

    @Test public void mergesOverlappingListingsIntoTheCorrectNestedTree() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies();
        merged.add(comment("a", "t1_p", more("t1_a", "b")));
        merged.add(comment("b", "t1_a", comment("c", "t1_b")));
        merged.add(comment("c", "t1_b"));
        List<ThingListing> roots = merged.build(ids("a", "b"), ids());
        assertEquals(1, roots.size());
        assertEquals("a", roots.get(0).getData().getId());
        ThingListing[] children = roots.get(0).getData().getReplies().getData().getChildren();
        assertEquals(1, children.length);
        assertEquals("b", children[0].getData().getId());
        assertEquals("c", children[0].getData().getReplies().getData().getChildren()[0].getData().getId());
    }

    @Test public void preservesRequestedSiblingOrderAndDeduplicatesResponses() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies();
        merged.add(comment("a", "t1_p")); merged.add(comment("b", "t1_p")); merged.add(comment("a", "t1_p"));
        List<ThingListing> roots = merged.build(ids("a", "b"), ids());
        assertEquals(2, roots.size());
        assertEquals("a", roots.get(0).getData().getId());
        assertEquals("b", roots.get(1).getData().getId());
    }

    @Test public void retainsUnrequestedIdsWhileRemovingUnavailableAttemptedIds() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies();
        merged.add(more("t1_p", "a", "b", "c"));
        List<ThingListing> roots = merged.build(ids("a"), ids());
        assertEquals(Arrays.asList("b", "c"), roots.get(0).getData().getChildren());
        assertEquals("b", roots.get(0).getData().getId());
    }

    @Test public void doesNotKeepAnEndlessMoreLinkForUnavailableComments() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies(); merged.add(more("t1_p", "a"));
        assertTrue(merged.build(ids("a"), ids()).isEmpty());
    }

    @Test public void avoidsDuplicatingVisibleCommentsButKeepsNewDescendants() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies();
        merged.add(comment("a", "t1_p", comment("b", "t1_a")));
        List<ThingListing> roots = merged.build(ids("a"), ids("t1_a"));
        assertEquals(1, roots.size());
        assertEquals("b", roots.get(0).getData().getId());
    }

    @Test public void carriesUnrequestedBatchRemainderIntoAnotherLoadLink() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies();
        ThingInfo stub = more("t1_p", "a", "b").getData();
        merged.addRemaining(stub, Arrays.asList("b", "c"));
        List<ThingListing> roots = merged.build(ids("a"), ids());
        assertEquals("t1_p", roots.get(0).getData().getParent_id());
        assertEquals(Arrays.asList("b", "c"), roots.get(0).getData().getChildren());
    }

    @Test public void malformedResponsesAreErrorsRatherThanSilentSuccess() throws Exception {
        try { MoreCommentReplies.comments(new Listing[0]); fail(); }
        catch (IOException expected) { }
    }

    @Test public void rejectsCyclesBeforeRecursiveRendering() throws Exception {
        MoreCommentReplies merged = new MoreCommentReplies();
        merged.add(comment("a", "t1_b")); merged.add(comment("b", "t1_a"));
        try { merged.build(ids(), ids()); fail(); } catch (IOException expected) { }
    }

    @Test public void fetchesTheSelectedSubtreeWithoutAlreadyVisibleAncestors() throws Exception {
        assertEquals("https://www.reddit.com/comments/post1/z/child2/.json?sort=controversial&context=0",
                MoreCommentReplies.commentUrl("post1", "child2", "sort=controversial"));
        try { MoreCommentReplies.commentUrl("post1", "../bad", "sort=new"); fail(); }
        catch (IOException expected) { }
    }
}
