package protonbackup.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Walks a source folder and reports the regular files in it. */
public final class Scanner {

    private final Duration settleTime;

    /** A file written just now is left until the next run, so a half-written file is not uploaded. */
    public Scanner() {
        this(Duration.ofSeconds(5));
    }

    public Scanner(Duration settleTime) {
        this.settleTime = settleTime;
    }

    /**
     * Symbolic links are skipped, not followed; unreadable files and folders are skipped; files
     * modified within the settle time are left out. Dotfiles are included.
     *
     * <p>Only regular files are reported. The .NET original would also list pipes and sockets, which
     * the CLI would then block on while reading them.
     *
     * @throws NoSuchFileException when {@code root} is not an existing folder
     */
    public List<ScannedFile> scan(String root) throws IOException {
        var start = Path.of(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(start)) {
            throw new NoSuchFileException(start.toString(), null, "Source folder does not exist: " + start);
        }
        // A symlink as the root itself is followed once (as the original does); below it, none are.
        var realRoot = start.toRealPath();
        var cutoff = Instant.now().minus(settleTime);
        var found = new ArrayList<ScannedFile>();

        Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                var modified = attributes.lastModifiedTime();
                if (modified.toInstant().isAfter(cutoff)) return FileVisitResult.CONTINUE;
                found.add(new ScannedFile(
                        realRoot.relativize(file).toString(), attributes.size(), modified.toMillis(), FileIdentity.inode(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE; // unreadable or vanished: skip it
            }
        });
        return found;
    }

    /** New path, or a different size or mtime than in the table: upload again. */
    public static boolean needsUpload(ScannedFile scanned, TrackedFile tracked) {
        return tracked == null
                || !FileStatus.SYNCED.equals(tracked.status())
                || tracked.size() != scanned.size()
                || tracked.modifiedUnixMs() != scanned.modifiedUnixMs();
    }
}
