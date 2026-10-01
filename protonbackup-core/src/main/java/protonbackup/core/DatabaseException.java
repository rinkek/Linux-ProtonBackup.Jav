package protonbackup.core;

import java.sql.SQLException;

/** An unexpected SQLite failure; wraps the checked {@link SQLException} so callers stay readable. */
public final class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, SQLException cause) {
        super(message + ": " + cause.getMessage(), cause);
    }
}
