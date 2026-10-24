package clre20.pMonitor.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 從 GitHub Releases 檢查新版本。
 * 結果快取 10 分鐘、不定時輪詢；同一時間只會有一個查詢；網路請求只用一條背景執行緒。
 */
public final class UpdateChecker {

    public static final String REPO = "clre20/PMonitor";
    public static final String PAGE = "https://github.com/" + REPO + "/releases";
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;

    public enum Status { NEWER, LATEST, DEV, FAILED }

    /** FAILED 時 reasonKey 是 messages.yml 的 version.reason-* 鍵，reasonArg 是附帶的值。 */
    public record Result(Status status, String current, String latest, String reasonKey, String reasonArg) {
    }

    private final String current;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PMonitor-UpdateChecker");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .executor(this.executor)
        .build();

    private CompletableFuture<Result> cached;
    private long cachedAt;

    public UpdateChecker(String current) {
        this.current = current;
    }

    /** 取得檢查結果（10 分鐘內重複呼叫直接用快取）。 */
    public synchronized CompletableFuture<Result> check() {
        long now = System.currentTimeMillis();
        if (this.cached != null && (!this.cached.isDone() || now - this.cachedAt < CACHE_MILLIS)) {
            return this.cached;
        }
        this.cachedAt = now;
        this.cached = fetch();
        return this.cached;
    }

    public void shutdown() {
        this.executor.shutdownNow();
    }

    private CompletableFuture<Result> fetch() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PMonitor/" + this.current)
            .GET()
            .build();
        return this.client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(res -> {
                if (res.statusCode() == 404) {
                    return failed("version.reason-no-release", "");
                }
                if (res.statusCode() != 200) {
                    return failed("version.reason-http", String.valueOf(res.statusCode()));
                }
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                if (!json.has("tag_name")) {
                    return failed("version.reason-no-tag", "");
                }
                String latest = strip(json.get("tag_name").getAsString());
                int cmp = compare(latest, strip(this.current));
                Status status = cmp > 0 ? Status.NEWER : cmp < 0 ? Status.DEV : Status.LATEST;
                return new Result(status, this.current, latest, null, null);
            })
            .exceptionally(t -> failed("version.reason-offline", ""));
    }

    private Result failed(String key, String arg) {
        return new Result(Status.FAILED, this.current, null, key, arg);
    }

    /** 去掉標籤的 v 前綴。 */
    static String strip(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }

    /** 比較 1.2.3 格式的版本號。 */
    public static int compare(String a, String b) {
        String[] x = a.split("[.\\-+]");
        String[] y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0;
            int yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            String d = s.replaceAll("[^0-9]", "");
            return d.isEmpty() ? 0 : Integer.parseInt(d.length() > 9 ? d.substring(0, 9) : d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
