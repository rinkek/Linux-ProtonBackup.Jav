package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Ported from RemotePathTests.cs. */
class RemotePathTest {

    @Test
    void combineBuildsPosixPath() {
        assertEquals("/my-files/Backup/docs/notities", RemotePath.combine("/my-files/Backup", "docs/notities"));
    }

    @Test
    void combineWithEmptyRelativePathReturnsParent() {
        assertEquals("/my-files/Backup", RemotePath.combine("/my-files/Backup", ""));
    }

    @Test
    void slashInNameIsEscaped() {
        assertEquals("/my-files/a\\/b", RemotePath.join("/my-files", "a/b"));
    }

    @Test
    void parentAndNameSplitThePath() {
        assertEquals("/my-files/docs", RemotePath.parentOf("/my-files/docs/een.txt"));
        assertEquals("een.txt", RemotePath.nameOf("/my-files/docs/een.txt"));
    }

    @Test
    void parentOfATopLevelPathIsTheRoot() {
        assertEquals("/", RemotePath.parentOf("/my-files"));
        assertEquals("/", RemotePath.parentOf("noslash"));
    }

    @Test
    void combineIgnoresEmptySegmentsAndTrailingSlashes() {
        assertEquals("/my-files/Backup/a/b", RemotePath.combine("/my-files/Backup/", "a//b/"));
    }
}
