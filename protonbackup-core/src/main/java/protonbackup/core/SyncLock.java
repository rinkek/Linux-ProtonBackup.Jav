package protonbackup.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

/**
 * Only one sync run at a time: a second start stops with a short message.
 *
 * <p>An operating-system lock on {@code sync.lock}, which the OS releases when the process dies,
 * so a crashed run never leaves the lock held. The file contains the PID of the holder and is only
 * rewritten <em>after</em> the lock is held: truncating it earlier would wipe the PID of a running
 * holder even when this attempt fails.
 *
 * <p>Nothing else in the JVM may open {@code sync.lock}: closing any other channel on the same
 * file silently releases a lock held through this one (a documented property of the JDK's file locks).
 */
public final class SyncLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SyncLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** The lock, or {@code null} when another run holds it (in this or in another process). */
    public static SyncLock tryAcquire(AppPaths paths) throws IOException {
        paths.ensureCreated();
        var channel = FileChannel.open(
                paths.lockPath(), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)), 0);
            return new SyncLock(channel, lock);
        } catch (OverlappingFileLockException alreadyHeldByThisJvm) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
