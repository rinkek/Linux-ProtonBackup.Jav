package protonbackup.core;

/**
 * Settings for fetching and updating the CLI, with a "restore defaults" button in the UI as a
 * safety net for when the page changes location or shape.
 */
public final class CliSettings {

    public static final String DEFAULT_VERSION_PAGE_URL = "https://proton.me/download/drive/cli/index.html";
    public static final String DEFAULT_DOWNLOAD_TEMPLATE =
            "https://proton.me/download/drive/cli/{version}/{platform}/proton-drive";
    public static final String DEFAULT_PLATFORM = "linux-x64";
    public static final String BASELINE_PLATFORM = "linux-x64-baseline";

    private final SettingsStore store;

    public CliSettings(SettingsStore store) {
        this.store = store;
    }

    public String versionPageUrl() {
        return orDefault("cli_version_page_url", DEFAULT_VERSION_PAGE_URL);
    }

    public void setVersionPageUrl(String value) {
        store.setSetting("cli_version_page_url", value);
    }

    public String downloadTemplate() {
        return orDefault("cli_download_template", DEFAULT_DOWNLOAD_TEMPLATE);
    }

    public void setDownloadTemplate(String value) {
        store.setSetting("cli_download_template", value);
    }

    public String platform() {
        return orDefault("cli_platform", DEFAULT_PLATFORM);
    }

    public void setPlatform(String value) {
        store.setSetting("cli_platform", value);
    }

    public boolean skipChecksum() {
        return "1".equals(store.getSetting("cli_skip_checksum"));
    }

    public void setSkipChecksum(boolean value) {
        store.setSetting("cli_skip_checksum", value ? "1" : "0");
    }

    /** The newest version seen on the version page, or {@code null}. */
    public String lastSeenVersion() {
        return store.getSetting("cli_last_seen_version");
    }

    public void setLastSeenVersion(String value) {
        store.setSetting("cli_last_seen_version", value == null ? "" : value);
    }

    /** Remembers which version was dismissed, so "Later" only comes back for an even newer one. */
    public String dismissedVersion() {
        return store.getSetting("cli_dismissed_version");
    }

    public void setDismissedVersion(String value) {
        store.setSetting("cli_dismissed_version", value == null ? "" : value);
    }

    /** Resets the four fetch settings; the last seen and dismissed versions are left as they are. */
    public void restoreDefaults() {
        setVersionPageUrl(DEFAULT_VERSION_PAGE_URL);
        setDownloadTemplate(DEFAULT_DOWNLOAD_TEMPLATE);
        setPlatform(DEFAULT_PLATFORM);
        setSkipChecksum(false);
    }

    public String buildDownloadUrl(String version, String platform) {
        return downloadTemplate().replace("{version}", version).replace("{platform}", platform);
    }

    private String orDefault(String key, String fallback) {
        var value = store.getSetting(key);
        return value == null ? fallback : value;
    }
}
