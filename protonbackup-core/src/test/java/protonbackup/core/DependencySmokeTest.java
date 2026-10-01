package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/**
 * Scaffolding from phase 2: proves that the build pipeline runs tests and that the two
 * runtime dependencies load (sqlite-jdbc with its native library, and Jackson).
 * Removed in phase 14 once real tests exist.
 */
class DependencySmokeTest {

    @Test
    void sqliteNativeLibraryLoadsAndAnswers() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT sqlite_version()")) {
            rows.next();
            // RETURNING (used by Database.AddSource) needs SQLite 3.35 or newer.
            var parts = rows.getString(1).split("\\.");
            var atLeast335 = Integer.parseInt(parts[0]) > 3
                    || (Integer.parseInt(parts[0]) == 3 && Integer.parseInt(parts[1]) >= 35);
            assertEquals(true, atLeast335);
        }
    }

    @Test
    void jacksonParsesCaseInsensitively() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals("folder", mapper.readTree("{\"type\":\"folder\"}").get("type").asText());
    }
}
