package protonbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The inode of a file. The .NET original called {@code stat(2)} through P/Invoke and read a
 * hard-coded offset out of the raw buffer; the JDK exposes it directly.
 */
public final class FileIdentity {

    private FileIdentity() {}

    /** The inode number, or {@code null} when it cannot be read (the file vanished, or no unix view). */
    public static Long inode(Path path) {
        try {
            return (Long) Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        }
    }
}
