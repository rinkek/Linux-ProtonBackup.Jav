package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * One real round trip through the actual {@code proton-drive} CLI and a real Proton account. Off by
 * default, because it uploads two small files; run it with {@code -Dprotonbackup.realProton=true}. It
 * also skips itself when there is no CLI or no signed-in session on the machine.
 *
 * <p>Safe by construction: the database and lock live in a temporary folder, the remote folder has a
 * random name, and it is moved to the trash again afterwards, even when an assertion fails.
 */
@EnabledIfSystemProperty(named = "protonbackup.realProton", matches = "true")
class RealProtonEndToEndTest {

    @TempDir Path temp;

    @Test
    void aRealRoundTripUploadsConfirmsAndIsANoOpTheSecondTime() throws Exception {
        var cliPath = ProtonDriveCli.locate(null, AppPaths.fromEnvironment());
        assumeTrue(cliPath != null, "no proton-drive CLI is installed on this machine");
        var cli = new ProtonDriveCli(cliPath);
        assumeTrue(cli.checkSession() == SessionState.ACTIVE, "the proton-drive CLI has no active session");

        var remoteRoot = "/my-files/JavaCoreIntegrationTest-" + UUID.randomUUID().toString().substring(0, 8);
        var local = Files.createDirectories(temp.resolve("real-e2e"));
        var old = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600));
        Files.writeString(local.resolve("hello.txt"), "hello from the real end-to-end test");
        Files.createDirectories(local.resolve("sub"));
        Files.writeString(local.resolve("sub/nested.txt"), "nested content ünï 日本");
        Files.setLastModifiedTime(local.resolve("hello.txt"), old);
        Files.setLastModifiedTime(local.resolve("sub/nested.txt"), old);

        var paths = AppPaths.forHome(temp.resolve("home"));
        try (var database = Database.open(paths)) {
            var source = database.addSource(local.toString(), remoteRoot);
            var engine = new SyncEngine(paths, database, cli, message -> {}, new Scanner(Duration.ZERO), Duration.ofSeconds(3));

            var result = engine.runOnce(null, new CancelToken());

            assertEquals(2, result.uploaded());
            assertEquals(0, result.failed());
            var tracked = database.getTrackedFiles(source);
            assertEquals(FileStatus.SYNCED, tracked.get("hello.txt").status());
            assertEquals(FileStatus.SYNCED, tracked.get("sub/nested.txt").status());

            // Ask the real CLI directly, not just our own database.
            var listing = cli.list(remoteRoot);
            assertNotNull(listing);
            assertTrue(listing.stream().filter(n -> !n.isFolder()).map(RemoteNode::fileName).collect(Collectors.toSet()).contains("hello.txt"));
            var nested = cli.list(remoteRoot + "/sub");
            assertEquals(List.of("nested.txt"), nested.stream().map(RemoteNode::fileName).toList());

            assertEquals(0, engine.runOnce(null, new CancelToken()).uploaded(), "a second round must be a no-op");
        } finally {
            cli.run(List.of("filesystem", "trash", remoteRoot));
        }
    }
}
