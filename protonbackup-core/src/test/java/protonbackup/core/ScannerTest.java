package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first six tests are ported from ScannerTests.cs. The rest cover symlinks, permissions, inode
 * identity and special files, which the plan flags as needing extra attention.
 */
class ScannerTest {

    private static final ScannedFile FILE = new ScannedFile("docs/een.txt", 100, 1_700_000_000_000L, null);

    private static Scanner immediate() {
        return new Scanner(Duration.ZERO);
    }

    private static List<String> names(List<ScannedFile> files) {
        return files.stream().map(ScannedFile::relativePath).sorted().toList();
    }

    @Test
    void unknownFileNeedsUpload() {
        assertTrue(Scanner.needsUpload(FILE, null));
    }

    @Test
    void unchangedSyncedFileIsSkipped() {
        assertFalse(Scanner.needsUpload(FILE, new TrackedFile(FILE.relativePath(), 100, 1_700_000_000_000L, FileStatus.SYNCED)));
    }

    @Test
    void changedSizeNeedsUpload() {
        assertTrue(Scanner.needsUpload(FILE, new TrackedFile(FILE.relativePath(), 99, 1_700_000_000_000L, FileStatus.SYNCED)));
    }

    @Test
    void changedModificationTimeNeedsUpload() {
        assertTrue(Scanner.needsUpload(FILE, new TrackedFile(FILE.relativePath(), 100, 1_600_000_000_000L, FileStatus.SYNCED)));
    }

    @Test
    void previousErrorIsRetried() {
        assertTrue(Scanner.needsUpload(FILE, new TrackedFile(FILE.relativePath(), 100, 1_700_000_000_000L, FileStatus.ERROR)));
    }

    @Test
    void recentlyWrittenFileIsLeftForTheNextRound(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("vers.txt"), "net geschreven");

        assertTrue(new Scanner().scan(directory.toString()).isEmpty());
        assertEquals(1, immediate().scan(directory.toString()).size());
    }

    // ---- symlinks, permissions, inode identity ------------------------------------------

    @Test
    void symlinkedFilesAreSkippedNotFollowed(@TempDir Path directory) throws Exception {
        var real = Files.writeString(directory.resolve("real.txt"), "data");
        Files.createSymbolicLink(directory.resolve("link.txt"), real);

        assertEquals(List.of("real.txt"), names(immediate().scan(directory.toString())));
    }

    @Test
    void symlinkedDirectoriesAreNotRecursedInto(@TempDir Path directory) throws Exception {
        var realDir = Files.createDirectory(directory.resolve("real_dir"));
        Files.writeString(realDir.resolve("inside.txt"), "data");
        Files.createSymbolicLink(directory.resolve("link_dir"), realDir);

        assertEquals(List.of("real_dir/inside.txt"), names(immediate().scan(directory.toString())));
    }

    @Test
    void aSymlinkAsTheRootItselfIsFollowedOnce(@TempDir Path directory) throws Exception {
        var realDir = Files.createDirectory(directory.resolve("real_dir"));
        Files.writeString(realDir.resolve("inside.txt"), "data");
        var link = Files.createSymbolicLink(directory.resolve("root_link"), realDir);

        assertEquals(List.of("inside.txt"), names(immediate().scan(link.toString())));
    }

    @Test
    void unreadableSubdirectoryIsSkippedNotRaised(@TempDir Path directory) throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "permission bits are not enforced for root");
        var blocked = Files.createDirectory(directory.resolve("blocked"));
        Files.writeString(blocked.resolve("secret.txt"), "data");
        Files.writeString(directory.resolve("visible.txt"), "data");
        Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("---------"));
        try {
            assertEquals(List.of("visible.txt"), names(immediate().scan(directory.toString())));
        } finally {
            Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void inodeIsStableAcrossARename(@TempDir Path directory) throws Exception {
        var original = Files.writeString(directory.resolve("old_name.txt"), "data");
        var before = immediate().scan(directory.toString()).get(0);

        Files.move(original, directory.resolve("new_name.txt"));
        var after = immediate().scan(directory.toString()).get(0);

        assertNotNull(before.inode());
        assertEquals(before.inode(), after.inode());
        assertFalse(before.relativePath().equals(after.relativePath()));
    }

    @Test
    void twoScansOfAnUnchangedFileReportTheSameInodeSizeAndMtime(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a.txt"), "data");

        var first = immediate().scan(directory.toString()).get(0);
        var second = immediate().scan(directory.toString()).get(0);

        assertEquals(first, second);
        assertEquals(4L, first.size());
    }

    @Test
    void scanRecursesIntoNestedDirectories(@TempDir Path directory) throws Exception {
        var nested = Files.createDirectories(directory.resolve("a/b/c"));
        Files.writeString(nested.resolve("deep.txt"), "data");

        assertEquals(List.of("a/b/c/deep.txt"), names(immediate().scan(directory.toString())));
    }

    @Test
    void dotfilesAreIncluded(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve(".hidden"), "data");
        Files.createDirectory(directory.resolve(".config"));
        Files.writeString(directory.resolve(".config/app.ini"), "data");

        assertEquals(List.of(".config/app.ini", ".hidden"), names(immediate().scan(directory.toString())));
    }

    @Test
    void nonAsciiAndAwkwardFileNamesSurviveAsTheyAre(@TempDir Path directory) throws Exception {
        var awkward = List.of("café.txt", "Ünïcödé Ärger.txt", "日本語.txt", "naïve 😀.txt", "with space.txt", "quote'd \"name\".txt");
        for (var name : awkward) Files.writeString(directory.resolve(name), "x");

        assertEquals(awkward.stream().sorted().toList(), names(immediate().scan(directory.toString())));
    }

    @Test
    void pipesAreSkippedBecauseTheCliWouldBlockOnThem(@TempDir Path directory) throws Exception {
        var made = new ProcessBuilder("mkfifo", directory.resolve("pipe").toString()).start().waitFor();
        assumeTrue(made == 0, "mkfifo is not available");
        Files.writeString(directory.resolve("regular.txt"), "data");

        assertEquals(List.of("regular.txt"), names(immediate().scan(directory.toString())));
    }

    @Test
    void emptyDirectoriesProduceNothing(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("empty/inner"));

        assertTrue(immediate().scan(directory.toString()).isEmpty());
    }

    @Test
    void modificationTimeIsReportedInMilliseconds(@TempDir Path directory) throws Exception {
        var file = Files.writeString(directory.resolve("a.txt"), "data");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_700_000_123_456L));

        assertEquals(1_700_000_123_456L, immediate().scan(directory.toString()).get(0).modifiedUnixMs());
    }

    @Test
    void scanThrowsWhenTheRootDoesNotExist(@TempDir Path directory) {
        assertThrows(NoSuchFileException.class, () -> immediate().scan(directory.resolve("does-not-exist").toString()));
    }

    @Test
    void scanThrowsWhenTheRootIsAFileNotAFolder(@TempDir Path directory) throws IOException {
        var file = Files.writeString(directory.resolve("a.txt"), "data");

        assertThrows(NoSuchFileException.class, () -> immediate().scan(file.toString()));
    }
}
