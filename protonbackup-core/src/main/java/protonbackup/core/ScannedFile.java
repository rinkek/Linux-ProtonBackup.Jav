package protonbackup.core;

/**
 * A file found by the scanner. {@code inode} is stored but not used for anything yet (renames are
 * not detected); it is {@code null} when it could not be read.
 */
public record ScannedFile(String relativePath, long size, long modifiedUnixMs, Long inode) {}
