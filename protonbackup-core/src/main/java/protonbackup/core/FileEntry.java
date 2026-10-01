package protonbackup.core;

import java.time.Instant;

/** A file as shown in the UI's folder tree. */
public record FileEntry(
        String relativePath,
        String name,
        long size,
        String status,
        Instant lastSyncUtc,
        String lastError) {}
