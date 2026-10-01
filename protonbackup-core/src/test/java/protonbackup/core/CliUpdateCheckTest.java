package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import protonbackup.core.CliUpdateCheck.UpdateStatus;

class CliUpdateCheckTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private static final class FakeSource implements CliVersionSource {
        String installed;
        CliRelease release;
        int fetchCalls;

        FakeSource(String installed, CliRelease release) {
            this.installed = installed;
            this.release = release;
        }

        @Override
        public String getInstalledVersion() {
            return installed;
        }

        @Override
        public CliRelease fetchRelease() {
            fetchCalls++;
            return release;
        }
    }

    private final FakeSettingsStore store = new FakeSettingsStore();
    private final List<String> messages = new ArrayList<>();

    private CliUpdateCheck check(FakeSource source) {
        return new CliUpdateCheck(store, source, messages::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CliRelease release(String version) {
        return new CliRelease(version, List.of());
    }

    @Test
    void isDueWhenNeverCheckedBefore() {
        assertTrue(check(new FakeSource(null, null)).isDue());
    }

    @Test
    void isDueRespectsTheDailyInterval() {
        store.setSetting("cli_last_check_utc", NOW.minus(Duration.ofHours(3)).toString());

        assertFalse(check(new FakeSource(null, null)).isDue());
    }

    @Test
    void isDueWhenTheLastCheckIsOlderThanADay() {
        store.setSetting("cli_last_check_utc", NOW.minus(Duration.ofDays(2)).toString());

        assertTrue(check(new FakeSource(null, null)).isDue());
    }

    @Test
    void isDueExactlyAtTheIntervalBoundary() {
        store.setSetting("cli_last_check_utc", NOW.minus(CliUpdateCheck.INTERVAL).toString());

        assertTrue(check(new FakeSource(null, null)).isDue());
    }

    @Test
    void anUnparseableStoredValueCountsAsDue() {
        store.setSetting("cli_last_check_utc", "not a timestamp");

        assertTrue(check(new FakeSource(null, null)).isDue());
    }

    @Test
    void theOriginalsSevenDigitTimestampFormatIsUnderstood() {
        store.setSetting("cli_last_check_utc", "2026-10-01T11:00:00.1234567Z");

        assertFalse(check(new FakeSource(null, null)).isDue());
    }

    @Test
    void checkSkipsTheNetworkWhenNotDueAndNotForced() throws Exception {
        store.setSetting("cli_last_check_utc", NOW.toString());
        var source = new FakeSource("0.8.0", release("0.9.0"));

        assertNull(check(source).check(false));
        assertEquals(0, source.fetchCalls);
    }

    @Test
    void forceBypassesTheDailyInterval() throws Exception {
        store.setSetting("cli_last_check_utc", NOW.toString());
        var source = new FakeSource("0.8.0", release("0.9.0"));

        var status = check(source).check(true);

        assertEquals(new UpdateStatus("0.8.0", "0.9.0", true, false), status);
        assertEquals(1, source.fetchCalls);
    }

    @Test
    void reportsUpToDateWhenVersionsMatch() throws Exception {
        var status = check(new FakeSource("0.9.0", release("0.9.0"))).check(true);

        assertFalse(status.updateAvailable());
    }

    @Test
    void noInstalledVersionIsNeverReportedAsAnUpdate() throws Exception {
        var status = check(new FakeSource(null, release("0.9.0"))).check(true);

        assertFalse(status.updateAvailable());
        assertNull(status.installed());
    }

    @Test
    void returnsNullWhenTheVersionPageCouldNotBeFetched() throws Exception {
        assertNull(check(new FakeSource("0.8.0", null)).check(true));
    }

    @Test
    void recordsTheCheckTimeEvenWhenThePageFails() throws Exception {
        check(new FakeSource("0.8.0", null)).check(true);

        assertEquals(NOW.toString(), store.getSetting("cli_last_check_utc"));
    }

    @Test
    void remembersTheLastSeenVersion() throws Exception {
        check(new FakeSource("0.8.0", release("0.9.0"))).check(true);

        assertEquals("0.9.0", new CliSettings(store).lastSeenVersion());
    }

    @Test
    void logsOnlyWhenANewerVersionIsFound() throws Exception {
        check(new FakeSource("0.9.0", release("0.9.0"))).check(true);
        assertTrue(messages.isEmpty());

        check(new FakeSource("0.8.0", release("0.9.0"))).check(true);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("0.9.0") && messages.get(0).contains("0.8.0"));
    }

    @Test
    void dismissMarksTheVersionUntilANewerOneAppears() throws Exception {
        var source = new FakeSource("0.8.0", release("0.9.0"));
        var check = check(source);

        check.dismiss("0.9.0");
        assertTrue(check.check(true).dismissed());

        source.release = release("0.10.0");
        var again = check.check(true);
        assertFalse(again.dismissed());
        assertNotNull(again.available());
    }
}
