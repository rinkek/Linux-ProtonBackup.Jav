package protonbackup.core;

/** A local folder that is backed up to a destination path on Proton Drive. */
public record SyncSource(long id, String localPath, String remotePath, boolean enabled) {}
