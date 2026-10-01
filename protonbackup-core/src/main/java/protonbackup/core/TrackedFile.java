package protonbackup.core;

/** What the database knows about a file. {@code status} is one of {@link FileStatus}. */
public record TrackedFile(String relativePath, long size, long modifiedUnixMs, String status) {}
