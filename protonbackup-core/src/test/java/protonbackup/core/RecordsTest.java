package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.junit.jupiter.api.Test;

/** Records must compare by value, like the C# records they replace. */
class RecordsTest {

    @Test
    void recordsHaveValueBasedEquality() {
        var a = new ScannedFile("docs/een.txt", 100, 1_700_000_000_000L, 42L);
        var b = new ScannedFile("docs/een.txt", 100, 1_700_000_000_000L, 42L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotSame(a, b);
    }

    @Test
    void statusConstantsAreThePlainStringsStoredInTheDatabase() {
        assertEquals("pending", FileStatus.PENDING);
        assertEquals("synced", FileStatus.SYNCED);
        assertEquals("error", FileStatus.ERROR);
        assertEquals("missing", FileStatus.MISSING);
    }

    @Test
    void blockedSyncResultReportsZeroCountsWithAReason() {
        assertEquals(new SyncResult(0, 0, 0, "already-running"), SyncResult.blocked("already-running"));
    }
}
