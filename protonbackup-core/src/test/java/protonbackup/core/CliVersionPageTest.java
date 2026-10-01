package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Ported from CliVersionPageTests.cs; the fixture is a real captured Proton response. */
class CliVersionPageTest {

    private static String realPage() throws Exception {
        try (var stream = CliVersionPageTest.class.getResourceAsStream("/fixtures/version-page-0.8.0.html")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheRealVersionPage() throws Exception {
        var release = CliVersionPage.parse(realPage());

        assertNotNull(release);
        assertEquals("0.8.0", release.version());
        assertEquals(9, release.downloads().size());
    }

    @Test
    void findsTheLinuxBuildWithItsChecksum() throws Exception {
        var download = CliVersionPage.parse(realPage()).forPlatform("linux-x64");

        assertNotNull(download);
        assertEquals("https://proton.me/download/drive/cli/0.8.0/linux-x64/proton-drive", download.url());
        assertEquals(
                "cf61c2688c45e1055d8add6221d9471a5a5b64bf3bcdb86460f5cb18414596cc4df3cdb6627c9097c94bec32a3c9915ada3211ef2ae5be33c46ebbc996ccaa28",
                download.sha512());
    }

    @Test
    void findsTheBaselineBuildUsedAfterAnIllegalInstruction() throws Exception {
        assertNotNull(CliVersionPage.parse(realPage()).forPlatform("linux-x64-baseline"));
    }

    @Test
    void returnsNothingWhenThePageIsUnrecognisable() {
        assertNull(CliVersionPage.parse("<html><body>Onderhoud</body></html>"));
        assertNull(CliVersionPage.parse(""));
        assertNull(CliVersionPage.parse(null));
    }

    @Test
    void returnsNothingWhenTheVersionIsPresentButTheTableIsEmpty() {
        assertNull(CliVersionPage.parse("<h1>Proton Drive CLI 0.9.0</h1><table><tbody></tbody></table>"));
    }

    @Test
    void toleratesAMissingChecksumColumn() {
        var release = CliVersionPage.parse("""
                <h1>Proton Drive CLI 0.9.0</h1>
                <table><tr><td>linux/x64</td><td><a href="https://example.test/proton-drive">link</a></td></tr></table>
                """);

        var download = release.forPlatform("linux-x64");
        assertNotNull(download);
        assertNull(download.sha512());
    }

    @ParameterizedTest
    @CsvSource({
        "0.9.0, 0.8.0, true",
        "0.10.0, 0.9.0, true",
        "1.0.0, 0.99.9, true",
        "0.8.0, 0.8.0, false",
        "0.7.9, 0.8.0, false",
    })
    void comparesVersionsAsNumbers(String candidate, String current, boolean expected) {
        assertEquals(expected, CliVersionPage.isNewer(candidate, current));
    }

    @Test
    void theFixtureIsByteIdenticalToTheOriginalCapture() throws Exception {
        var bytes = Files.readAllBytes(Path.of(CliVersionPageTest.class.getResource("/fixtures/version-page-0.8.0.html").toURI()));
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);

        assertEquals(
                "23b19c45ad51661fe101842df115d5073128fd73d44212ba55aa3b82fe0566c6",
                java.util.HexFormat.of().formatHex(digest));
    }
}
