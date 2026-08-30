package io.github.khayashi4337.micradrone.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RevisionClockTest {

    /**
     * Reopening a controller whose server-side revision had already advanced: the clock starts at 0
     * (the screen is new), takes that first echo, and must then send ABOVE the server's number - not
     * from 1 again, or the stale echoes still in flight would outrank the edits that follow.
     */
    @Test
    void acceptingAnEchoSeedsTheClockAboveTheServersRevision() {
        RevisionClock clock = new RevisionClock();
        assertTrue(clock.accept(5));
        assertEquals(6, clock.nextSend());
    }

    /** Mid-edit: echoes of earlier sends are refused, the echo of the latest send is taken. */
    @Test
    void onlyEchoesAtOrAboveTheLastSendAreAccepted() {
        RevisionClock clock = new RevisionClock();
        assertTrue(clock.accept(5)); // reopened a controller the server had already advanced to 5
        assertEquals(6, clock.nextSend());
        assertEquals(7, clock.nextSend());
        assertFalse(clock.accept(6), "an echo of the send before last is stale");
        assertTrue(clock.accept(7), "the echo of the latest send is current");
    }

    /** A refused echo must not drag the clock backwards - the next send still outranks everything. */
    @Test
    void refusingAnEchoLeavesTheClockAlone() {
        RevisionClock clock = new RevisionClock();
        for (int i = 0; i < 7; i++) {
            clock.nextSend(); // 1..7
        }
        assertFalse(clock.accept(3));
        assertEquals(8, clock.nextSend(), "a refused echo must not have reset the clock to 3");
    }

    /**
     * The echo of one's own latest send carries exactly that number, so equality has to be accepted
     * - with a strict {@code >} it would never be applied, and the very first sync of an untouched
     * controller (server 0, clock 0) would fail too.
     */
    @Test
    void anEchoEqualToTheLastSendIsAccepted() {
        RevisionClock clock = new RevisionClock();
        for (int i = 0; i < 7; i++) {
            clock.nextSend(); // 1..7
        }
        assertTrue(clock.accept(7));
    }

    @Test
    void theFirstSyncOfAnUntouchedControllerIsAccepted() {
        assertTrue(new RevisionClock().accept(0));
    }

    /**
     * Two players on one controller: the other viewer's write comes back with a revision above
     * anything this client issued, so it is taken rather than mistaken for a stale echo of one's
     * own - and this client's next send still outranks it. This is the property
     * {@code SetBreakpointsPayload}'s doc claims for the multi-viewer case.
     */
    @Test
    void anotherViewersWriteIsTakenAndStillOutrankedByTheNextSend() {
        RevisionClock clock = new RevisionClock();
        for (int i = 0; i < 7; i++) {
            clock.nextSend(); // 1..7
        }
        assertTrue(clock.accept(9), "another viewer's write carries a higher revision, not a stale one");
        assertEquals(10, clock.nextSend());
    }
}
