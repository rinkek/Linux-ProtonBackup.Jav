package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * SyncLock had no C# tests. The JDK tracks locks per JVM, so mutual exclusion between two runs
 * (two daemon processes) can only be proven with a second process; the in-JVM cases are separate.
 */
@Timeout(60)
class SyncLockTest {

    @TempDir Path home;

    private AppPaths paths() {
        return AppPaths.forHome(home);
    }

    private static Process startHolder(Path home) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(), home.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static String firstLine(Process process) throws Exception {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }

    @Test
    void tryAcquireSucceedsAndWritesThePid() throws Exception {
        try (var lock = SyncLock.tryAcquire(paths())) {
            assertNotNull(lock);
            assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(paths().lockPath()));
        }
    }

    @Test
    void aSecondAcquireFailsWhileTheFirstIsHeld() throws Exception {
        try (var first = SyncLock.tryAcquire(paths())) {
            assertNotNull(first);
            assertNull(SyncLock.tryAcquire(paths()));
        }
    }

    @Test
    void closingReleasesTheLockForALaterAcquire() throws Exception {
        SyncLock.tryAcquire(paths()).close();

        try (var second = SyncLock.tryAcquire(paths())) {
            assertNotNull(second);
        }
    }

    /** The lock file must not be truncated until the lock is held, or a failed attempt would wipe the holder's PID. */
    @Test
    void aFailedAcquireDoesNotTruncateTheExistingFile() throws Exception {
        try (var first = SyncLock.tryAcquire(paths())) {
            var before = Files.readString(paths().lockPath());

            assertNull(SyncLock.tryAcquire(paths()));

            assertEquals(before, Files.readString(paths().lockPath()));
        }
    }

    @Test
    void aLongerStalePidIsFullyReplaced() throws Exception {
        Files.createDirectories(paths().dataDir());
        Files.writeString(paths().lockPath(), "99999999999999999999");

        try (var lock = SyncLock.tryAcquire(paths())) {
            assertNotNull(lock);
            assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(paths().lockPath()));
        }
    }

    @Test
    void theLockCannotBeTakenWhileTheRetiredPathsRefuseToCreateFolders() {
        var retired = paths();
        retired.retire();

        assertThrows(IllegalStateException.class, () -> SyncLock.tryAcquire(retired));
        assertTrue(Files.notExists(retired.lockPath()));
    }

    // ---- mutual exclusion between processes -----------------------------------------------

    @Test
    void anotherProcessHoldingTheLockBlocksUs() throws Exception {
        var holder = startHolder(home);
        try {
            assertEquals("LOCKED", firstLine(holder));

            assertNull(SyncLock.tryAcquire(paths()));
            assertEquals(Long.toString(holder.pid()), Files.readString(paths().lockPath()), "the holder's PID must survive our attempt");
        } finally {
            holder.getOutputStream().close();
            holder.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void weBlockAnotherProcess() throws Exception {
        try (var ours = SyncLock.tryAcquire(paths())) {
            assertNotNull(ours);

            var other = startHolder(home);
            try {
                assertEquals("BUSY", firstLine(other));
            } finally {
                other.getOutputStream().close();
                other.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    /** The reason for an OS lock instead of an exclusive-create file: nothing is left stuck after a crash. */
    @Test
    void aCrashedHolderDoesNotLeaveTheLockStuck() throws Exception {
        var holder = startHolder(home);
        assertEquals("LOCKED", firstLine(holder));
        assertNull(SyncLock.tryAcquire(paths()));

        holder.destroyForcibly(); // SIGKILL: no chance to clean up
        holder.waitFor(10, TimeUnit.SECONDS);

        try (var lock = SyncLock.tryAcquire(paths())) {
            assertNotNull(lock, "the operating system must have released the dead process's lock");
            assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(paths().lockPath()));
        }
    }

    @Test
    void aHolderThatExitsNormallyReleasesTheLock() throws Exception {
        var holder = startHolder(home);
        assertEquals("LOCKED", firstLine(holder));
        holder.getOutputStream().close();
        assertTrue(holder.waitFor(10, TimeUnit.SECONDS));

        try (var lock = SyncLock.tryAcquire(paths())) {
            assertNotNull(lock);
        }
    }
}
