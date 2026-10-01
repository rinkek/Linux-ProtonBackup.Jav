package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIdentityTest {

    @Test
    void inodeMatchesTheFilesystemsOwnNumber(@TempDir Path directory) throws Exception {
        var file = Files.writeString(directory.resolve("a.txt"), "hello");

        var expected = Files.getAttribute(file, "unix:ino");

        assertNotNull(FileIdentity.inode(file));
        assertEquals(expected, FileIdentity.inode(file));
    }

    @Test
    void inodeIsNullForAMissingPath(@TempDir Path directory) {
        assertNull(FileIdentity.inode(directory.resolve("does-not-exist.txt")));
    }

    @Test
    void differentFilesHaveDifferentInodes(@TempDir Path directory) throws Exception {
        var a = Files.writeString(directory.resolve("a.txt"), "a");
        var b = Files.writeString(directory.resolve("b.txt"), "b");

        assertNotEquals(FileIdentity.inode(a), FileIdentity.inode(b));
    }

    @Test
    void aSymlinkHasItsOwnInodeNotTheTargets(@TempDir Path directory) throws Exception {
        var target = Files.writeString(directory.resolve("target.txt"), "a");
        var link = Files.createSymbolicLink(directory.resolve("link.txt"), target);

        assertNotEquals(FileIdentity.inode(target), FileIdentity.inode(link));
    }
}
