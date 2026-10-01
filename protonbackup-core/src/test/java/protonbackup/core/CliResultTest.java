package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The predicates gate real control flow (session expiry, folder-exists races), so they are pinned down. */
class CliResultTest {

    @Test
    void okIsTrueOnlyForExitCodeZero() {
        assertTrue(new CliResult(0, "", "").ok());
        assertFalse(new CliResult(1, "", "").ok());
    }

    @Test
    void outputPrefersStdoutAndOnlyAppendsStderrWhenPresent() {
        assertEquals("hello", new CliResult(0, "hello", "").output());
        assertEquals("hello", new CliResult(0, "hello", "   ").output());
        assertEquals("helloboom", new CliResult(1, "hello", "boom").output());
    }

    @Test
    void notLoggedInIsCaseInsensitivePlainText() {
        assertTrue(new CliResult(1, "You need to login first", "").notLoggedIn());
        assertTrue(new CliResult(1, "you NEED to LOGIN first", "").notLoggedIn());
        assertFalse(new CliResult(0, "{\"ok\": true}", "").notLoggedIn());
    }

    @Test
    void alreadyExistsAndNotFoundAreDetectedCaseInsensitively() {
        assertTrue(new CliResult(1, "A file or folder with that name already exists", "").alreadyExists());
        assertTrue(new CliResult(1, "Node not found: niveau1", "").notFound());
        assertFalse(new CliResult(0, "Transfer summary: 1 items", "").alreadyExists());
        assertFalse(new CliResult(0, "Transfer summary: 1 items", "").notFound());
    }
}
