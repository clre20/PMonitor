package clre20.pMonitor.update;

import clre20.pMonitor.PMonitor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 檢查 GitHub clre20/PMonitor 最新的正式 Release（不含草稿與預覽版）。
 * 只提示，不下載；結果快取 10 分鐘以免超過 GitHub 每小時 60 次的限制。
 */
public final class UpdateChecker {

    public enum State { NEWER, LATEST, NONE, FAILED }

    public record Result(State state, String current, String latest, String url, String error) {
    }

    private static final String API = "https://api.github.com/repos/clre20/PMonitor/releases/latest";
    private static final String RELEASES = "https://github.com/clre20/PMonitor/releases";
    private static final long CACHE_MILLIS = 10 * 60 * 1000L;

    private final PMonitor plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Result cached;
    private volatile long cachedAt;

    public UpdateChecker(PMonitor plugin) {
        this.plugin = plugin;
    }

    private String current() {
        return this.plugin.getPluginMeta().getVersion();
    }

    /** 背景檢查，完成後在主執行緒回呼。 */
    public void checkAsync(Consumer<Result> callback) {
        Result c = this.cached;
        if (c != null && System.currentTimeMillis() - this.cachedAt < CACHE_MILLIS) {
            callback.accept(c);
            return;
        }
        CompletableFuture.supplyAsync(this::fetch).thenAccept(result -> {
            if (result.state != State.FAILED) {
                this.cached = result;
                this.cachedAt = System.currentTimeMillis();
            }
            if (this.plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(this.plugin, () -> callback.accept(result));
            }
        });
    }

    private Result fetch() {
        String current = current();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PMonitor/" + current)
                .GET().build();
            HttpResponse<String> resp = this.http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return new Result(State.NONE, current, null, RELEASES, null);
            }
            if (resp.statusCode() != 200) {
                return new Result(State.FAILED, current, null, RELEASES, "HTTP " + resp.statusCode());
            }
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            String tag = o.has("tag_name") ? o.get("tag_name").getAsString() : null;
            String url = o.has("html_url") ? o.get("html_url").getAsString() : RELEASES;
            if (tag == null || tag.isBlank()) {
                return new Result(State.NONE, current, null, RELEASES, null);
            }
            String latest = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            State state = compare(latest, current) > 0 ? State.NEWER : State.LATEST;
            return new Result(state, current, latest, url, null);
        } catch (Exception e) {
            return new Result(State.FAILED, current, null, RELEASES, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : "：" + e.getMessage()));
        }
    }

    /** 比較版本號（1.2.10 > 1.2.9）。 */
    public static int compare(String a, String b) {
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? Long.parseLong(pa[i]) : 0;
            long y = i < pb.length && !pb[i].isEmpty() ? Long.parseLong(pb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    /** /cpm help、/cpm reload：顯示版本並檢查更新。 */
    public void sendVersion(CommandSender sender) {
        this.plugin.messages().send(sender, "version", "version", current());
        if (!this.plugin.config().updateCheck) {
            return;
        }
        this.plugin.messages().send(sender, "update-checking");
        checkAsync(result -> send(sender, result));
    }

    public void send(CommandSender sender, Result r) {
        if (sender instanceof Player p && !p.isOnline()) {
            return;
        }
        switch (r.state) {
            case NEWER -> this.plugin.messages().send(sender, "update-available", "latest", r.latest, "version", r.current, "url", r.url);
            case LATEST -> this.plugin.messages().send(sender, "update-latest", "version", r.current);
            case NONE -> this.plugin.messages().send(sender, "update-none");
            case FAILED -> this.plugin.messages().send(sender, "update-failed", "error", r.error);
        }
    }

    public void notifyOnJoin(Player player) {
        checkAsync(result -> {
            if (result.state == State.NEWER && player.isOnline()) {
                send(player, result);
            }
        });
    }

    public String consoleText(Result r) {
        return switch (r.state) {
            case NEWER -> "有新版本 " + r.latest + "（目前 " + r.current + "）：" + r.url;
            case LATEST -> "已是最新版本（" + r.current + "）";
            case NONE -> "GitHub 尚無發布版本";
            case FAILED -> "檢查更新失敗：" + r.error;
        };
    }
}
