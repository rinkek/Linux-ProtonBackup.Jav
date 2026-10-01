package protonbackup.core;

/**
 * The four values of {@code files.status}. The column is deliberately free text, as in the
 * original: nothing enforces these at the SQL level.
 */
public final class FileStatus {

    public static final String PENDING = "pending";
    public static final String SYNCED = "synced";
    public static final String ERROR = "error";
    public static final String MISSING = "missing";

    private FileStatus() {}
}
