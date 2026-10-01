package protonbackup.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import protonbackup.core.CliRelease.CliDownload;

/**
 * The version page is plain HTML with no machine-readable list, but it is very regular in shape:
 * the version is in the {@code h1} and each platform has its URL and SHA-512 in a table. The
 * parser is deliberately lenient so that small markup changes do not break it.
 */
public final class CliVersionPage {

    private static final int IGNORE_CASE = Pattern.CASE_INSENSITIVE;
    private static final int ALL = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern VERSION =
            Pattern.compile("<h1[^>]*>\\s*Proton\\s+Drive\\s+CLI\\s+([0-9][0-9A-Za-z.\\-]*)\\s*</h1>", IGNORE_CASE);
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", ALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", ALL);
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", IGNORE_CASE);
    private static final Pattern SHA512 = Pattern.compile("\\b([0-9a-f]{128})\\b", IGNORE_CASE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private CliVersionPage() {}

    /** The release described by the page, or {@code null} when the page is not recognised. */
    public static CliRelease parse(String html) {
        if (html == null || html.isBlank()) return null;

        var version = VERSION.matcher(html);
        if (!version.find()) return null;

        var downloads = new ArrayList<CliDownload>();
        var rows = ROW.matcher(html);
        while (rows.find()) {
            var row = rows.group(1);

            var cells = new ArrayList<String>();
            var cellMatcher = CELL.matcher(row);
            while (cellMatcher.find()) cells.add(cellMatcher.group(1));
            if (cells.size() < 2) continue;

            var platform = text(cells.get(0));
            var url = HREF.matcher(row);
            if (platform.isEmpty() || !url.find()) continue;

            var checksum = SHA512.matcher(row);
            downloads.add(new CliDownload(
                    platform,
                    url.group(1).trim(),
                    checksum.find() ? checksum.group(1).toLowerCase(Locale.ROOT) : null));
        }
        return downloads.isEmpty() ? null : new CliRelease(version.group(1), downloads);
    }

    /** Compares versions as sequences of numbers, so 0.10.0 is newer than 0.9.0. */
    public static boolean isNewer(String candidate, String current) {
        var left = numbers(candidate);
        var right = numbers(current);
        for (var i = 0; i < Math.max(left.size(), right.size()); i++) {
            long a = i < left.size() ? left.get(i) : 0L;
            long b = i < right.size() ? right.get(i) : 0L;
            if (a != b) return a > b;
        }
        return false;
    }

    private static String text(String html) {
        return TAG.matcher(html).replaceAll("").trim();
    }

    /** The leading numeric parts of a version such as {@code 0.9.0-rc1+abc}. */
    private static List<Long> numbers(String version) {
        var result = new ArrayList<Long>();
        for (var part : version.split("[.\\-+]", -1)) {
            if (part.isEmpty() || !part.chars().allMatch(c -> c >= '0' && c <= '9')) break;
            result.add(Long.parseLong(part));
        }
        return result;
    }
}
