package protonbackup.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * HTTPS access for the CLI installer. Redirects are followed: {@link HttpClient} does not do that
 * unless asked, and the download URLs may redirect.
 */
public final class HttpFetcher implements CliInstaller.TextFetcher, CliInstaller.Downloader {

    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private volatile HttpClient client;

    /**
     * Built on first use. Building an {@link HttpClient} costs about 110 ms (it sets up TLS) and starts
     * a thread that sits in native code, which makes the JVM wait 300 ms longer when it exits. The
     * daemon builds this object for every command, but only the daily update check and the installer
     * ever go to the network.
     */
    private HttpClient client() {
        var existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
            }
            return client;
        }
    }

    /** For the tests: has the client been built yet? */
    boolean clientBuilt() {
        return client != null;
    }

    @Override
    public String fetchText(String url) throws IOException, InterruptedException {
        var response = client().send(request(url, PAGE_TIMEOUT), HttpResponse.BodyHandlers.ofByteArray());
        check(url, response.statusCode());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Override
    public void downloadTo(String url, Path destination) throws IOException, InterruptedException {
        var response = client().send(request(url, DOWNLOAD_TIMEOUT), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            check(url, response.statusCode());
            Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static HttpRequest request(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    }

    private static void check(String url, int status) throws IOException {
        if (status < 200 || status > 299) throw new IOException("HTTP " + status + " for " + url);
    }
}
