package protonbackup.core;

/** Proton Drive always uses POSIX paths; a {@code /} inside a name is escaped with a backslash. */
public final class RemotePath {

    private RemotePath() {}

    public static String join(String parent, String name) {
        return trimTrailingSlashes(parent) + "/" + escape(name);
    }

    public static String combine(String parent, String relativeDirectory) {
        var result = trimTrailingSlashes(parent);
        for (var segment : relativeDirectory.split("/")) {
            if (!segment.isEmpty()) result = join(result, segment);
        }
        return result;
    }

    public static String escape(String name) {
        return name.replace("/", "\\/");
    }

    public static String parentOf(String path) {
        var index = path.lastIndexOf('/');
        return index <= 0 ? "/" : path.substring(0, index);
    }

    public static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String trimTrailingSlashes(String text) {
        var end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') end--;
        return text.substring(0, end);
    }
}
