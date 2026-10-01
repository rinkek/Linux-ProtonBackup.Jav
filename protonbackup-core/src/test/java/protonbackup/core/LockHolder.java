package protonbackup.core;

import java.nio.file.Path;

/** Test helper, run in a second JVM: takes the sync lock, reports, and holds it until stdin closes. */
public final class LockHolder {

    private LockHolder() {}

    public static void main(String[] args) throws Exception {
        var lock = SyncLock.tryAcquire(AppPaths.forHome(Path.of(args[0])));
        System.out.println(lock == null ? "BUSY" : "LOCKED");
        System.out.flush();
        System.in.read(); // returns at end-of-file, i.e. when the parent closes our stdin
        if (lock != null) lock.close();
    }
}
