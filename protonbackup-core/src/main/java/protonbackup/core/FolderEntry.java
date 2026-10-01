package protonbackup.core;

/** A folder as shown in the UI's folder tree, with a status summary of everything below it. */
public record FolderEntry(
        String name, String relativePath, int fileCount, int errorCount, int pendingCount) {}
