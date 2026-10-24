package clre20.pMonitor.storage;

import clre20.pMonitor.util.Log;
import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.PluginConfig;
import clre20.pMonitor.util.Fmt;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 錄製檔容量管理：
 * <ul>
 *   <li>超過提醒值：照常錄製，定期提醒 OP 等級足夠的線上玩家與主控台</li>
 *   <li>超過強停值：所有錄製正確存檔後停止，之後不能開始新錄製；降回以下後自動恢復</li>
 *   <li>超過保留天數的錄製檔自動刪除</li>
 * </ul>
 */
public final class StorageMonitor {

    private final PMonitor plugin;
    private final Path dir;
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile long usedBytes;
    private long lastWarnMillis;
    private boolean warned;
    /**
     * 強停後、被停止的錄製都存檔（壓縮）完成時的容量。壓縮會讓容量變小，
     * 所以不能一降到上限以下就恢復，否則會反覆停止與恢復；
     * 必須真的釋放空間（刪檔、過期清除）才恢復。-1 表示尚未量測。
     */
    private long settledUsage = -1;
    private boolean reloadedWhileBlocked;

    public StorageMonitor(PMonitor plugin, Path dir) {
        this.plugin = plugin;
        this.dir = dir;
    }

    /** 顯示用的容量：上次掃描結果，或「已存檔＋錄製中」的即時估計，取較大者。 */
    public long usedBytes() {
        long live = this.plugin.index().totalSize() + this.plugin.recordings().activeBytes();
        return Math.max(this.usedBytes, live);
    }

    /** 判斷門檻用的容量（實際掃描磁碟的結果）。 */
    public long scannedBytes() {
        return this.usedBytes;
    }

    /** /cpm reload：管理員可能調高了上限，下次檢查時若已低於上限就恢復。 */
    public void onReload() {
        if (this.plugin.recordings().isStorageBlocked()) {
            this.reloadedWhileBlocked = true;
        }
        requestCheck();
    }

    /** 在背景重新計算容量，完成後回主執行緒判斷門檻。 */
    public void requestCheck() {
        if (!this.checking.compareAndSet(false, true)) {
            return;
        }
        // 開始量測時是否還有錄製在寫檔／存檔（此時量到的容量不能當作「存檔完成後的容量」）
        boolean busy = this.plugin.recordings().hasPendingSaves() || !this.plugin.recordings().activeRecorders().isEmpty();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            long size;
            try {
                size = directorySize(this.dir);
            } finally {
                this.checking.set(false);
            }
            long finalSize = size;
            if (this.plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(this.plugin, () -> evaluate(finalSize, busy));
            }
        });
    }

    private void evaluate(long size, boolean busyAtStart) {
        this.usedBytes = size;
        PluginConfig c = this.plugin.config();
        long total = size;
        boolean overStop = c.stopSizeBytes > 0 && total >= c.stopSizeBytes;
        boolean overWarn = c.warnSizeBytes > 0 && total >= c.warnSizeBytes;

        if (overStop && !this.plugin.recordings().isStorageBlocked()) {
            List<String> stopped = this.plugin.recordings().blockForStorage();
            String players = stopped.isEmpty() ? this.plugin.messages().plain("none") : String.join(this.plugin.messages().plain("list-separator"), stopped);
            warnAdmins("storage-stop", "used", Fmt.size(total), "stop", Fmt.size(c.stopSizeBytes), "players", players);
            this.lastWarnMillis = System.currentTimeMillis();
            this.warned = true;
            this.settledUsage = -1;
            this.reloadedWhileBlocked = false;
            return;
        }
        if (this.plugin.recordings().isStorageBlocked()) {
            if (this.settledUsage < 0) {
                // 等被停止的錄製都存檔完成，記下那時的容量
                if (!busyAtStart && !this.plugin.recordings().hasPendingSaves() && this.plugin.recordings().activeRecorders().isEmpty()) {
                    this.settledUsage = total;
                }
            }
            // 封鎖期間沒有錄製在寫入，容量只會因刪檔而變小；4 KB 只是避免量測誤差
            long margin = 4096;
            boolean freed = this.settledUsage >= 0 && total < this.settledUsage - margin;
            boolean limitRaised = this.reloadedWhileBlocked;
            if (!overStop && (freed || limitRaised || c.stopSizeBytes == 0)) {
                this.settledUsage = -1;
                this.reloadedWhileBlocked = false;
                List<String> resumed = this.plugin.recordings().unblockStorage();
                if (resumed.isEmpty()) {
                    warnAdmins("storage-unblocked");
                } else {
                    warnAdmins("storage-resumed", "players", String.join("、", resumed));
                }
            }
        }
        if (overWarn || overStop) {
            long now = System.currentTimeMillis();
            if (!this.warned || now - this.lastWarnMillis >= c.warnIntervalMinutes * 60_000L) {
                this.lastWarnMillis = now;
                this.warned = true;
                if (overStop) {
                    warnAdmins("storage-stop-reminder", "used", Fmt.size(total), "stop", Fmt.size(c.stopSizeBytes));
                } else {
                    warnAdmins("storage-warn", "used", Fmt.size(total), "warn", Fmt.size(c.warnSizeBytes), "stop", stopText(c));
                }
            }
        } else {
            this.warned = false;
        }
    }

    private String stopText(PluginConfig c) {
        return c.stopSizeBytes > 0 ? Fmt.size(c.stopSizeBytes) : this.plugin.messages().raw("storage-no-limit");
    }

    /** 玩家進服時：容量超過提醒值就提示（只給 OP 等級足夠的人）。 */
    public void onJoin(Player player) {
        PluginConfig c = this.plugin.config();
        long total = usedBytes();
        if (!isAdmin(player)) {
            return;
        }
        if (this.plugin.recordings().isStorageBlocked()) {
            this.plugin.messages().send(player, "storage-stop-reminder", "used", Fmt.size(total), "stop", Fmt.size(c.stopSizeBytes));
        } else if (c.warnSizeBytes > 0 && total >= c.warnSizeBytes) {
            this.plugin.messages().send(player, "storage-warn", "used", Fmt.size(total), "warn", Fmt.size(c.warnSizeBytes), "stop", stopText(c));
        }
    }

    private void warnAdmins(String key, Object... args) {
        this.plugin.getLogger().warning(this.plugin.messages().plain(key, args));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isAdmin(p)) {
                this.plugin.messages().send(p, key, args);
            }
        }
    }

    /** OP 等級是否達到 warn-op-level（依 ops.json 的 level）。 */
    public boolean isAdmin(Player player) {
        if (!player.isOp()) {
            return false;
        }
        Integer level = opLevels().get(player.getUniqueId());
        int required = this.plugin.config().warnOpLevel;
        return level == null ? required <= 4 : level >= required;
    }

    private long opsCacheTime;
    private Map<UUID, Integer> opsCache = new HashMap<>();

    private Map<UUID, Integer> opLevels() {
        long now = System.currentTimeMillis();
        if (now - this.opsCacheTime < 30_000L) {
            return this.opsCache;
        }
        this.opsCacheTime = now;
        Map<UUID, Integer> out = new HashMap<>();
        File ops = new File(Bukkit.getWorldContainer(), "ops.json");
        if (!ops.exists()) {
            ops = new File("ops.json");
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(ops.toPath(), StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement el : (JsonArray) root) {
                    JsonObject o = el.getAsJsonObject();
                    out.put(UUID.fromString(o.get("uuid").getAsString()), o.has("level") ? o.get("level").getAsInt() : 4);
                }
            }
        } catch (Exception ignored) {
        }
        this.opsCache = out;
        return out;
    }

    // ---------------------------------------------------------------- 保留天數

    /** 刪除超過保留天數的錄製檔（背景執行）。 */
    public void cleanupOld() {
        int days = this.plugin.config().retentionDays;
        if (days <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            int removed = 0;
            for (RecordingIndex.Info info : this.plugin.index().all()) {
                long time = info.end() > 0 ? info.end() : info.start();
                if (time < cutoff) {
                    try {
                        Files.deleteIfExists(info.path());
                        this.plugin.index().remove(info.name());
                        removed++;
                    } catch (IOException e) {
                        Log.warn("retention-delete-failed", "file", info.name(), "error", e.getMessage());
                    }
                }
            }
            if (removed > 0) {
                Log.info("retention-deleted", "count", removed, "days", days);
                requestCheck();
            }
        });
    }

    public static long directorySize(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }
}
