package io.github.khayashi4337.micradrone.construction.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimBookTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final String OW = "minecraft:overworld";
    private static final Box HERE = new Box(0, 60, 0, 10, 80, 10);

    @Test
    void boxesThatShareOneBlockOverlap() {
        assertTrue(ClaimBook.overlaps(new Box(0, 0, 0, 5, 5, 5), new Box(5, 5, 5, 9, 9, 9)));
        assertFalse(ClaimBook.overlaps(new Box(0, 0, 0, 5, 5, 5), new Box(6, 0, 0, 9, 5, 5)));
        assertEquals(new Box(0, 0, 0, 9, 9, 9), ClaimBook.union(new Box(0, 0, 0, 5, 5, 5), new Box(5, 5, 5, 9, 9, 9)));
    }

    @Test
    void anotherClaimInTheSameDimensionIsRefusedAndStaysRefusedAfterItsJobEnds() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        List<Issue> issues = book.check(B, OW, new Box(5, 60, 5, 20, 80, 20), null);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP), issues.stream().map(Issue::code).toList());
        assertEquals("claim-a", issues.get(0).data().get("claim"));
        assertTrue(book.check(B, "minecraft:the_nether", new Box(5, 60, 5, 20, 80, 20), null).isEmpty(), "other dimension");
        assertTrue(book.check(A, OW, HERE, "claim-a").isEmpty(), "a MODIFY of the claim itself");
        book.release("claim-a");
        assertTrue(book.check(B, OW, HERE, null).isEmpty(), "released claims no longer protect");
        assertTrue(book.find("claim-a").orElseThrow().released());
    }

    @Test
    void anOwnerHoldsAtMostTheLimit() {
        ClaimBook book = new ClaimBook(2);
        book.reserve("c1", A, OW, new Box(0, 0, 0, 1, 1, 1), new Box(0, 0, 0, 1, 1, 1), 0L);
        book.reserve("c2", A, OW, new Box(10, 0, 0, 11, 1, 1), new Box(10, 0, 0, 11, 1, 1), 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_LIMIT),
                book.check(A, OW, new Box(20, 0, 0, 21, 1, 1), null).stream().map(Issue::code).toList());
        assertTrue(book.check(B, OW, new Box(20, 0, 0, 21, 1, 1), null).isEmpty());
        assertThrows(IllegalStateException.class, () -> book.reserve("c1", A, OW, HERE, HERE, 0L));
    }

    @Test
    void theClaimAtAPositionIsFoundByItsOperatingBox() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, new Box(0, 60, 0, 10, 120, 10), 0L);
        assertEquals("claim-a", book.claimAt(OW, new IntPos(3, 100, 3)).orElseThrow().claimId());
        assertTrue(book.claimAt(OW, new IntPos(30, 100, 3)).isEmpty());
        assertEquals(1, book.active().size());
    }

    @Test
    void workOnAnExistingClaimNeedsItLiveHereAndOurs() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(), book.checkOwned("claim-a", A, OW));
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-a", B, OW).get(0).code(), "another owner");
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-a", A, "minecraft:the_nether").get(0).code());
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-x", A, OW).get(0).code(), "no such claim");
        book.release("claim-a");
        assertEquals(IssueCode.E_CLAIM_INVALID, book.checkOwned("claim-a", A, OW).get(0).code(), "released");
    }

    @Test
    void aGrowingModifyWidensItsClaimAndOthersMayNotMoveIn() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        Box wider = new Box(0, 60, 0, 20, 80, 10);
        assertEquals(new Box(0, 60, 0, 20, 80, 10), book.grow("claim-a", wider, wider).operatingBox());
        assertEquals(IssueCode.E_CLAIM_OVERLAP,
                book.check(B, OW, new Box(15, 60, 0, 16, 70, 5), null).get(0).code(), "the new part is claimed too");
    }

    // Boxes are inclusive on both ends, so sharing exactly one layer (new.maxA == claim.minA) already overlaps,
    // while sitting one block further out (new.maxA + 1 == claim.minA) shares no block and must pass. Each test
    // below pins one of the six boundary comparisons in ClaimBook.overlaps (3 axes x both sides).

    @Test
    void boxesTouchingAtOneLayerOnAxisAFromBelowOverlap() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(-5, 60, 0, 0, 80, 10), null).stream().map(Issue::code).toList(),
                "the layer a=0 belongs to both boxes");
        assertTrue(book.check(B, OW, new Box(-5, 60, 0, -1, 80, 10), null).isEmpty(),
                "adjacent boxes share no block");
    }

    @Test
    void boxesTouchingAtOneLayerOnAxisAFromAboveOverlap() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(10, 60, 0, 15, 80, 10), null).stream().map(Issue::code).toList(),
                "the layer a=10 belongs to both boxes");
        assertTrue(book.check(B, OW, new Box(11, 60, 0, 15, 80, 10), null).isEmpty(),
                "adjacent boxes share no block");
    }

    @Test
    void boxesTouchingAtOneLayerOnAxisBFromBelowOverlap() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(0, 40, 0, 10, 60, 10), null).stream().map(Issue::code).toList(),
                "the layer b=60 belongs to both boxes");
        assertTrue(book.check(B, OW, new Box(0, 40, 0, 10, 59, 10), null).isEmpty(),
                "adjacent boxes share no block");
    }

    @Test
    void boxesTouchingAtOneLayerOnAxisBFromAboveOverlap() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(0, 80, 0, 10, 100, 10), null).stream().map(Issue::code).toList(),
                "the layer b=80 belongs to both boxes");
        assertTrue(book.check(B, OW, new Box(0, 81, 0, 10, 100, 10), null).isEmpty(),
                "adjacent boxes share no block");
    }

    @Test
    void boxesTouchingAtOneLayerOnAxisCFromBelowOverlap() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(0, 60, -5, 10, 80, 0), null).stream().map(Issue::code).toList(),
                "the layer c=0 belongs to both boxes");
        assertTrue(book.check(B, OW, new Box(0, 60, -5, 10, 80, -1), null).isEmpty(),
                "adjacent boxes share no block");
    }

    @Test
    void boxesTouchingAtOneLayerOnAxisCFromAboveOverlap() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        book.reserve("claim-a", A, OW, HERE, HERE, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(0, 60, 10, 10, 80, 15), null).stream().map(Issue::code).toList(),
                "the layer c=10 belongs to both boxes");
        assertTrue(book.check(B, OW, new Box(0, 60, 11, 10, 80, 15), null).isEmpty(),
                "adjacent boxes share no block");
    }

    @Test
    void theOperatingBoxIsGuardedOnAxisABeyondTheWorldBox() {
        ClaimBook book = new ClaimBook(ClaimBook.DEFAULT_MAX_CLAIMS_PER_OWNER);
        Box operating = new Box(-3, 0, 0, 13, 200, 10);
        book.reserve("claim-a", A, OW, HERE, operating, 0L);
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(-8, 60, 0, -3, 80, 10), null).stream().map(Issue::code).toList(),
                "the operating box reaches a=-3 even though the world box starts at a=0");
        assertTrue(book.check(B, OW, new Box(-8, 60, 0, -4, 80, 10), null).isEmpty(),
                "adjacent to the operating box shares no block");
        assertEquals(List.of(IssueCode.E_CLAIM_OVERLAP),
                book.check(B, OW, new Box(13, 60, 0, 18, 80, 10), null).stream().map(Issue::code).toList(),
                "the operating box reaches a=13 even though the world box ends at a=10");
        assertTrue(book.check(B, OW, new Box(14, 60, 0, 18, 80, 10), null).isEmpty(),
                "adjacent to the operating box shares no block");
    }
}
