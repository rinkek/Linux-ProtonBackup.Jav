package protonbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sqlite.SQLiteConfig;

/**
 * The only channel between the daemon and the UI: sources, tracked files, run history and
 * settings in one SQLite file (WAL mode, so the UI can read while the daemon writes).
 *
 * <p>All methods are {@code synchronized}: the UI uses one instance from several threads, and a
 * transaction must not interleave with another thread's statements.
 */
public final class Database implements SettingsStore, AutoCloseable {

    private static final int BUSY_TIMEOUT_MILLIS = 5000;

    private final Connection connection;

    /** Opens (and if needed creates) the database at {@code file}. */
    public Database(Path file) {
        try {
            var parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);

            var config = new SQLiteConfig();
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.enforceForeignKeys(true);
            // JDBC has no default busy timeout: without one, the UI reading while the daemon
            // commits would fail with SQLITE_BUSY instead of waiting a moment.
            config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);

            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties());
            createSchema();
        } catch (SQLException e) {
            throw new DatabaseException("Could not open the database " + file, e);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Opens the app's own database, creating the data folders first. */
    public static Database open(AppPaths paths) throws IOException {
        paths.ensureCreated();
        return new Database(paths.databasePath());
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sources (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        local_path  TEXT NOT NULL UNIQUE,
                        remote_path TEXT NOT NULL,
                        enabled     INTEGER NOT NULL DEFAULT 1
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS files (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_id      INTEGER NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
                        rel_path       TEXT NOT NULL,
                        size           INTEGER NOT NULL,
                        mtime_unix_ms  INTEGER NOT NULL,
                        inode          INTEGER,
                        last_sync_utc  TEXT,
                        status         TEXT NOT NULL,
                        last_error     TEXT,
                        UNIQUE(source_id, rel_path)
                    )""");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_files_status ON files(source_id, status)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS runs (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        started_utc   TEXT NOT NULL,
                        finished_utc  TEXT,
                        uploaded      INTEGER NOT NULL DEFAULT 0,
                        failed        INTEGER NOT NULL DEFAULT 0,
                        result        TEXT
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settings (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
        }
    }

    // ---- sources --------------------------------------------------------------------------

    /** Adds a source, or updates the destination when the local folder is already known. */
    public synchronized long addSource(String localPath, String remotePath) {
        var fullPath = Path.of(localPath).toAbsolutePath().normalize().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sources (local_path, remote_path, enabled) VALUES (?1, ?2, 1) "
                        + "ON CONFLICT(local_path) DO UPDATE SET remote_path = ?2 RETURNING id")) {
            statement.setString(1, fullPath);
            statement.setString(2, remotePath);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new DatabaseException("addSource", e);
        }
    }

    public synchronized List<SyncSource> getSources(boolean onlyEnabled) {
        var sql = "SELECT id, local_path, remote_path, enabled FROM sources"
                + (onlyEnabled ? " WHERE enabled = 1" : "") + " ORDER BY id";
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            var result = new ArrayList<SyncSource>();
            while (rows.next()) {
                result.add(new SyncSource(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getLong(4) != 0));
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("getSources", e);
        }
    }

    public List<SyncSource> getSources() {
        return getSources(false);
    }

    public synchronized void setSourceEnabled(long sourceId, boolean enabled) {
        update("UPDATE sources SET enabled = ?1 WHERE id = ?2", enabled ? 1 : 0, sourceId);
    }

    /** Removes a source; its tracked files go with it (ON DELETE CASCADE). */
    public synchronized void removeSource(long sourceId) {
        update("DELETE FROM sources WHERE id = ?1", sourceId);
    }

    // ---- files ----------------------------------------------------------------------------

    public synchronized Map<String, TrackedFile> getTrackedFiles(long sourceId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rel_path, size, mtime_unix_ms, status FROM files WHERE source_id = ?1")) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new HashMap<String, TrackedFile>();
                while (rows.next()) {
                    result.put(rows.getString(1), new TrackedFile(
                            rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getTrackedFiles", e);
        }
    }

    /** Inserts new files and resets changed ones to pending, in one transaction. */
    public synchronized void upsertPending(long sourceId, Collection<ScannedFile> files) {
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO files (source_id, rel_path, size, mtime_unix_ms, inode, status, last_error)
                    VALUES (?1, ?2, ?3, ?4, ?5, 'pending', NULL)
                    ON CONFLICT(source_id, rel_path) DO UPDATE SET
                        size = ?3, mtime_unix_ms = ?4, inode = ?5, status = 'pending', last_error = NULL""")) {
                for (var file : files) {
                    statement.setLong(1, sourceId);
                    statement.setString(2, file.relativePath());
                    statement.setLong(3, file.size());
                    statement.setLong(4, file.modifiedUnixMs());
                    if (file.inode() == null) statement.setNull(5, Types.INTEGER);
                    else statement.setLong(5, file.inode());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }, "upsertPending");
    }

    /**
     * Sets everything back to pending, for example when something on Proton was thrown away that the
     * database knows nothing about. Files that disappeared locally are left alone: there is nothing
     * to upload for them. During the run itself the CLI skips whatever already matches by content.
     *
     * @param sourceId one source, or {@code null} for all of them
     */
    public synchronized int markAllPending(Long sourceId) {
        var sql = "UPDATE files SET status = 'pending', last_error = NULL WHERE status <> 'missing'"
                + (sourceId == null ? "" : " AND source_id = ?1");
        return sourceId == null ? update(sql) : update(sql, sourceId);
    }

    public int markAllPending() {
        return markAllPending(null);
    }

    public synchronized List<TrackedFile> getPending(long sourceId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rel_path, size, mtime_unix_ms, status FROM files "
                        + "WHERE source_id = ?1 AND status IN ('pending','error') ORDER BY rel_path")) {
            statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<TrackedFile>();
                while (rows.next()) {
                    result.add(new TrackedFile(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getPending", e);
        }
    }

    public synchronized void markSynced(long sourceId, Collection<String> relativePaths) {
        var now = Instant.now().toString();
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE files SET status = 'synced', last_sync_utc = ?1, last_error = NULL "
                            + "WHERE source_id = ?2 AND rel_path = ?3")) {
                for (var path : relativePaths) {
                    statement.setString(1, now);
                    statement.setLong(2, sourceId);
                    statement.setString(3, path);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }, "markSynced");
    }

    public synchronized void markError(long sourceId, Collection<String> relativePaths, String message) {
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE files SET status = 'error', last_error = ?1 WHERE source_id = ?2 AND rel_path = ?3")) {
                for (var path : relativePaths) {
                    statement.setString(1, message);
                    statement.setLong(2, sourceId);
                    statement.setString(3, path);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }, "markError");
    }

    /**
     * Files that disappeared locally are marked, but never touched on Proton. A temporary table
     * holds the present paths: an {@code IN (...)} list would hit SQLite's parameter limit on large scans.
     *
     * @return how many files were newly marked missing
     */
    public synchronized int markMissing(long sourceId, Collection<String> presentPaths) {
        var affected = new int[1];
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TEMP TABLE IF NOT EXISTS present (rel_path TEXT PRIMARY KEY)");
                statement.executeUpdate("DELETE FROM present");
            }
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT OR IGNORE INTO present (rel_path) VALUES (?1)")) {
                for (var path : presentPaths) {
                    insert.setString(1, path);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE files SET status = 'missing' WHERE source_id = ?1 AND status <> 'missing' "
                            + "AND rel_path NOT IN (SELECT rel_path FROM present)")) {
                update.setLong(1, sourceId);
                affected[0] = update.executeUpdate();
            }
        }, "markMissing");
        return affected[0];
    }

    // ---- runs -----------------------------------------------------------------------------

    public synchronized long startRun() {
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO runs (started_utc) VALUES (?1) RETURNING id")) {
            statement.setString(1, Instant.now().toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException e) {
            throw new DatabaseException("startRun", e);
        }
    }

    public synchronized void finishRun(long runId, int uploaded, int failed, String result) {
        update("UPDATE runs SET finished_utc = ?1, uploaded = ?2, failed = ?3, result = ?4 WHERE id = ?5",
                Instant.now().toString(), uploaded, failed, result, runId);
    }

    // ---- settings -------------------------------------------------------------------------

    @Override
    public synchronized String getSetting(String key) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM settings WHERE key = ?1")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getSetting", e);
        }
    }

    @Override
    public synchronized void setSetting(String key, String value) {
        update("INSERT INTO settings (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = ?2",
                key, value);
    }

    // ---- display for the UI ---------------------------------------------------------------

    public synchronized List<RunRecord> getRecentRuns(int limit) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, started_utc, finished_utc, uploaded, failed, result FROM runs ORDER BY id DESC LIMIT ?1")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<RunRecord>();
                while (rows.next()) {
                    result.add(new RunRecord(
                            rows.getLong(1),
                            Instant.parse(rows.getString(2)),
                            instantOrNull(rows.getString(3)),
                            rows.getInt(4),
                            rows.getInt(5),
                            rows.getString(6)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getRecentRuns", e);
        }
    }

    /** Number of files per status, for one source or (when {@code sourceId} is null) for all. */
    public synchronized Map<String, Integer> getStatusCounts(Long sourceId) {
        var sql = "SELECT status, COUNT(*) FROM files" + (sourceId == null ? "" : " WHERE source_id = ?1")
                + " GROUP BY status";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (sourceId != null) statement.setLong(1, sourceId);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new HashMap<String, Integer>();
                while (rows.next()) result.put(rows.getString(1), rows.getInt(2));
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getStatusCounts", e);
        }
    }

    public Map<String, Integer> getStatusCounts() {
        return getStatusCounts(null);
    }

    /** Folders are only loaded when you expand them, with a summarised status per folder. */
    public synchronized List<FolderEntry> getChildFolders(long sourceId, String relativeDirectory) {
        var prefix = directoryPrefix(relativeDirectory);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT substr(rest, 1, instr(rest, '/') - 1) AS folder,
                       COUNT(*),
                       SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END)
                FROM (SELECT substr(rel_path, length(?1) + 1) AS rest, status
                      FROM files WHERE source_id = ?2 AND rel_path LIKE ?3 ESCAPE '\\')
                WHERE instr(rest, '/') > 0
                GROUP BY folder ORDER BY folder""")) {
            statement.setString(1, prefix);
            statement.setLong(2, sourceId);
            statement.setString(3, escapeLike(prefix) + "%");
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<FolderEntry>();
                while (rows.next()) {
                    var name = rows.getString(1);
                    result.add(new FolderEntry(name, prefix + name, rows.getInt(2), rows.getInt(3), rows.getInt(4)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getChildFolders", e);
        }
    }

    /** The files directly in a folder (not in its subfolders). */
    public synchronized List<FileEntry> getFilesIn(long sourceId, String relativeDirectory) {
        var prefix = directoryPrefix(relativeDirectory);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rel_path, size, status, last_sync_utc, last_error FROM files
                WHERE source_id = ?1 AND rel_path LIKE ?2 ESCAPE '\\'
                  AND instr(substr(rel_path, length(?3) + 1), '/') = 0
                ORDER BY rel_path""")) {
            statement.setLong(1, sourceId);
            statement.setString(2, escapeLike(prefix) + "%");
            statement.setString(3, prefix);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<FileEntry>();
                while (rows.next()) {
                    var relative = rows.getString(1);
                    result.add(new FileEntry(
                            relative,
                            relative.substring(relative.lastIndexOf('/') + 1),
                            rows.getLong(2),
                            rows.getString(3),
                            instantOrNull(rows.getString(4)),
                            rows.getString(5)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getFilesIn", e);
        }
    }

    /** Failed files. Like the original, the last sync time is never reported here. */
    public synchronized List<FileEntry> getFailedFiles(int limit) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rel_path, size, status, last_error FROM files WHERE status = 'error' "
                        + "ORDER BY rel_path LIMIT ?1")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new ArrayList<FileEntry>();
                while (rows.next()) {
                    var relative = rows.getString(1);
                    result.add(new FileEntry(
                            relative,
                            relative.substring(relative.lastIndexOf('/') + 1),
                            rows.getLong(2),
                            rows.getString(3),
                            null,
                            rows.getString(4)));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DatabaseException("getFailedFiles", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException("close", e);
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private static String directoryPrefix(String relativeDirectory) {
        var end = relativeDirectory.length();
        while (end > 0 && relativeDirectory.charAt(end - 1) == '/') end--;
        return end == 0 ? "" : relativeDirectory.substring(0, end) + "/";
    }

    /** Escapes LIKE wildcards so a folder named {@code a_b} does not also match {@code axb}. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instantOrNull(String text) {
        return text == null ? null : Instant.parse(text);
    }

    /** Runs one statement with positional parameters; returns the number of changed rows. */
    private int update(String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException(sql, e);
        }
    }

    @FunctionalInterface
    private interface SqlBody {
        void run() throws SQLException;
    }

    private void inTransaction(SqlBody body, String what) {
        try {
            connection.setAutoCommit(false);
            try {
                body.run();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DatabaseException(what, e);
        }
    }
}
