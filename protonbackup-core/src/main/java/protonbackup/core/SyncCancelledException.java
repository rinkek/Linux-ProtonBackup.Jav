package protonbackup.core;

/** A sync run stopped at a checkpoint because cancellation was requested. Not an error. */
public final class SyncCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SyncCancelledException() {
        super("The sync run was cancelled.");
    }
}
