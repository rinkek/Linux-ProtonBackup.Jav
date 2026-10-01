package protonbackup.core;

/** Counts and outcome of one sync run. */
public record SyncResult(int uploaded, int failed, int missing, String outcome) {

    /** A run that did not start: another run is active, or the session is not usable. */
    public static SyncResult blocked(String reason) {
        return new SyncResult(0, 0, 0, reason);
    }
}
