package protonbackup.core;

import java.util.List;

/** One published version of the Proton Drive CLI with its downloads. */
public record CliRelease(String version, List<CliDownload> downloads) {

    /** The download for a platform such as {@code linux-x64}; {@code null} when the page lists none. */
    public CliDownload forPlatform(String platform) {
        for (var download : downloads) {
            if (normalise(download.platform()).equalsIgnoreCase(platform)) return download;
        }
        return null;
    }

    /** The table lists {@code linux/x64}, the download URL uses {@code linux-x64}. */
    public static String normalise(String platform) {
        return platform.replace('/', '-');
    }

    /** {@code sha512} is {@code null} when the page lists no checksum for this build. */
    public record CliDownload(String platform, String url, String sha512) {}
}
