package protonbackup.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import protonbackup.core.RunRecord;
import protonbackup.core.SessionState;

class DescriptionsTest {

    private static final ZoneId PLUS_TWO = ZoneOffset.ofHours(2);

    @Test
    void runResultsAreWrittenInPlainLanguage() {
        assertEquals("completed", Descriptions.runResult("ok"));
        assertEquals("partly failed", Descriptions.runResult("partial"));
        assertEquals("cancelled", Descriptions.runResult("cancelled"));
        assertEquals("error", Descriptions.runResult("error"));
        assertEquals("skipped", Descriptions.runResult("already-running"));
        assertEquals("session expired", Descriptions.runResult("session-expired"));
        assertEquals("session unknown", Descriptions.runResult("session-unknown"));
        assertEquals("running", Descriptions.runResult(null));
        assertEquals("something-new", Descriptions.runResult("something-new"));
    }

    @Test
    void sessionStates() {
        assertEquals("signed in", Descriptions.session(SessionState.ACTIVE));
        assertEquals("session expired", Descriptions.session(SessionState.EXPIRED));
        assertEquals("unknown", Descriptions.session(SessionState.UNKNOWN));
    }

    @Test
    void timesAreShownInTheGivenZone() {
        assertEquals("29-09 22:46", Descriptions.shortTime(Instant.parse("2026-09-29T20:46:00Z"), PLUS_TWO));
    }

    @Test
    void lastRunOfAFinishedRun() {
        var run = new RunRecord(1, Instant.parse("2026-09-29T20:45:00Z"), Instant.parse("2026-09-29T20:46:00Z"), 3, 0, "ok");

        assertEquals("29-09 22:46 — 3 uploaded, 0 failed (completed)", Descriptions.lastRun(List.of(run), PLUS_TWO));
    }

    @Test
    void lastRunOfARunThatIsStillGoing() {
        var run = new RunRecord(1, Instant.parse("2026-09-29T20:45:00Z"), null, 0, 0, null);

        assertEquals("started 29-09 22:45, still running", Descriptions.lastRun(List.of(run), PLUS_TWO));
    }

    @Test
    void lastRunWithoutRuns() {
        assertEquals("no runs yet", Descriptions.lastRun(List.of(), PLUS_TWO));
    }

    @Test
    void onlyTheNewestRunIsDescribed() {
        var newest = new RunRecord(2, Instant.parse("2026-09-30T10:00:00Z"), Instant.parse("2026-09-30T10:01:00Z"), 1, 1, "partial");
        var older = new RunRecord(1, Instant.parse("2026-09-29T10:00:00Z"), Instant.parse("2026-09-29T10:01:00Z"), 5, 0, "ok");

        assertEquals("30-09 12:01 — 1 uploaded, 1 failed (partly failed)", Descriptions.lastRun(List.of(newest, older), PLUS_TWO));
    }
}
