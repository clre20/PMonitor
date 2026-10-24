package clre20.pMonitor.record;

import clre20.pMonitor.util.Log;
import clre20.pMonitor.PMonitor;
import clre20.pMonitor.data.PlayerDataStore;
import clre20.pMonitor.net.ClientState;
import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.nms.Ref;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/** 管理所有錄製：開始、停止、待命、自動錄製、自動分段、關服存檔。 */
public final class RecordingManager {

    /** 依容量分段時，每段最短的錄製時間（1 分鐘）。 */
    private static final long MIN_SPLIT_TICKS = 20L * 60L;

    public enum StartResult { STARTED, WAITING, ARMED, ALREADY_RECORDING, ALREADY_ARMED, BLOCKED, UNAVAILABLE, FAILED }

    /** 等待玩家進入遊戲後才開始的錄製。 */
    private static final class Pending {
        final int part;
        final long deadline;

        Pending(int part, long deadline) {
            this.part = part;
            this.deadline = deadline;
        }
    }

    private final PMonitor plugin;
    private final Path recordingsDir;
    private final Map<UUID, Recorder> active = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Integer> parts = new HashMap<>();
    private final List<CompletableFuture<RecordingFile>> saving = new ArrayList<>();
    /** 因容量達上限而被停止、容量恢復後要自動繼續錄製的玩家。 */
    private final Set<UUID> storageStopped = new LinkedHashSet<>();
    private volatile boolean storageBlocked;
    private long tickCounter;

    public RecordingManager(PMonitor plugin, Path recordingsDir) {
        this.plugin = plugin;
        this.recordingsDir = recordingsDir;
    }

    // ================================================================ 查詢

    public boolean isRecording(UUID uuid) {
        return this.active.containsKey(uuid) || this.pending.containsKey(uuid);
    }

    public Recorder recorder(UUID uuid) {
        return this.active.get(uuid);
    }

    public boolean isWaiting(UUID uuid) {
        return this.pending.containsKey(uuid);
    }

    public Collection<Recorder> activeRecorders() {
        return new ArrayList<>(this.active.values());
    }

    public Set<UUID> pendingPlayers() {
        return new LinkedHashSet<>(this.pending.keySet());
    }

    public boolean isStorageBlocked() {
        return this.storageBlocked;
    }

    public boolean hasPendingSaves() {
        synchronized (this.saving) {
            return !this.saving.isEmpty();
        }
    }

    public long activeBytes() {
        long total = 0;
        for (Recorder r : this.active.values()) {
            total += r.bytesWritten();
        }
        return total;
    }

    // ================================================================ 開始

    /** 開始錄製線上玩家；玩家尚未完全進入遊戲時會等待。 */
    public StartResult start(Player player) {
        if (!Nms.ready()) {
            return StartResult.UNAVAILABLE;
        }
        if (this.storageBlocked) {
            return StartResult.BLOCKED;
        }
        UUID uuid = player.getUniqueId();
        if (isRecording(uuid)) {
            return StartResult.ALREADY_RECORDING;
        }
        this.pending.put(uuid, new Pending(1, this.tickCounter + 20 * 60));
        return tryStartPending(player) ? StartResult.STARTED : StartResult.WAITING;
    }

    /** 離線玩家：設定下次進服錄製。 */
    public StartResult arm(UUID uuid, String name) {
        PlayerDataStore.Entry e = this.plugin.playerData().getOrCreate(uuid, name);
        if (e.armed) {
            return StartResult.ALREADY_ARMED;
        }
        e.armed = true;
        this.plugin.playerData().save(e);
        return StartResult.ARMED;
    }

    public boolean disarm(UUID uuid) {
        PlayerDataStore.Entry e = this.plugin.playerData().get(uuid);
        if (e == null || !e.armed) {
            return false;
        }
        e.armed = false;
        this.plugin.playerData().save(e);
        return true;
    }

    private boolean tryStartPending(Player player) {
        UUID uuid = player.getUniqueId();
        Pending p = this.pending.get(uuid);
        if (p == null) {
            return false;
        }
        ClientState state = this.plugin.taps().state(uuid);
        if (state == null) {
            state = this.plugin.taps().bind(player);
        }
        boolean ready;
        if (state == null) {
            ready = false;
        } else {
            synchronized (state) {
                ready = state.isPlaying();
            }
        }
        if (!ready) {
            return false;
        }
        this.pending.remove(uuid);
        try {
            RecordingFile file = RecordingFile.create(this.recordingsDir, uuid, player.getName(), p.part,
                worldName(), Bukkit.getMinecraftVersion(), dataVersion(), protocolVersion());
            Recorder recorder = new Recorder(this.plugin.getLogger(), player, state, file);
            recorder.start(player);
            this.active.put(uuid, recorder);
            this.parts.put(uuid, p.part);
            PlayerDataStore.Entry e = this.plugin.playerData().getOrCreate(uuid, player.getName());
            if (e.armed) {
                e.armed = false;
                this.plugin.playerData().save(e);
            }
            if (p.part == 1 && this.plugin.config().notifyPlayer) {
                this.plugin.messages().send(player, "notify-start");
            }
            Log.info("record-start", "player", player.getName(), "file", file.baseName());
            return true;
        } catch (Throwable t) {
            Log.severe("record-start-failed", t, "player", player.getName(), "error", Ref.rootMessage(t));
            this.plugin.notifyAdmins("record-error", "player", player.getName(), "error", Ref.rootMessage(t));
            return false;
        }
    }

    // ================================================================ 停止

    /**
     * 停止錄製（或取消等待中的錄製）。
     *
     * @param onSaved 存檔完成後在主執行緒呼叫（可為 null）；失敗時傳入 null
     * @return 是否有東西被停止
     */
    public boolean stop(UUID uuid, Recorder.StopReason reason, Consumer<RecordingFile> onSaved) {
        boolean any = this.pending.remove(uuid) != null;
        Recorder r = this.active.remove(uuid);
        if (r == null) {
            return any;
        }
        if (reason != Recorder.StopReason.SPLIT) {
            this.parts.remove(uuid);
        }
        CompletableFuture<RecordingFile> future = r.stop(reason);
        synchronized (this.saving) {
            this.saving.add(future);
        }
        future.whenComplete((file, error) -> {
            synchronized (this.saving) {
                this.saving.remove(future);
            }
            if (error == null) {
                this.plugin.index().add(file.zipFile());
            }
            if (!this.plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (error != null) {
                    this.plugin.notifyAdmins("record-save-failed", "player", r.playerName(), "error", Ref.rootMessage(error));
                }
                if (onSaved != null) {
                    onSaved.accept(error == null ? file : null);
                }
                this.plugin.storage().requestCheck();
            });
        });
        if (reason != Recorder.StopReason.SPLIT && reason != Recorder.StopReason.SHUTDOWN && this.plugin.config().notifyPlayer) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                this.plugin.messages().send(p, "notify-stop");
            }
        }
        Log.info("record-stop", "player", r.playerName(), "reason", Log.text("reason." + reason.name().toLowerCase(java.util.Locale.ROOT)));
        return true;
    }

    // ================================================================ 事件

    public void onJoin(Player player) {
        PlayerDataStore.Entry e = this.plugin.playerData().getOrCreate(player.getUniqueId(), player.getName());
        e.name = player.getName();
        e.lastSeen = System.currentTimeMillis();
        this.plugin.playerData().save(e);
        if ((e.armed || e.autoRecord) && !this.storageBlocked && Nms.ready() && !isRecording(player.getUniqueId())) {
            this.pending.put(player.getUniqueId(), new Pending(1, this.tickCounter + 20 * 60));
        }
    }

    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        stop(uuid, Recorder.StopReason.QUIT, null);
        this.pending.remove(uuid);
        PlayerDataStore.Entry e = this.plugin.playerData().get(uuid);
        if (e != null) {
            e.lastSeen = System.currentTimeMillis();
            this.plugin.playerData().save(e);
        }
    }

    // ================================================================ 每 tick

    public void tick() {
        this.tickCounter++;
        // 等待中的錄製
        if (!this.pending.isEmpty()) {
            Iterator<Map.Entry<UUID, Pending>> it = new HashMap<>(this.pending).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Pending> en = it.next();
                Player p = Bukkit.getPlayer(en.getKey());
                if (p == null) {
                    if (this.tickCounter > en.getValue().deadline) {
                        this.pending.remove(en.getKey());
                    }
                    continue;
                }
                if (!tryStartPending(p) && this.tickCounter > en.getValue().deadline) {
                    this.pending.remove(en.getKey());
                    Log.warn("record-wait-timeout", "player", p.getName());
                }
            }
        }
        if (this.active.isEmpty()) {
            return;
        }
        long maxTicks = this.plugin.config().maxDurationMinutes * 60L * 20L;
        long maxBytes = this.plugin.config().maxFileSizeBytes;
        for (Recorder r : new ArrayList<>(this.active.values())) {
            Player p = Bukkit.getPlayer(r.uuid());
            if (p == null) {
                stop(r.uuid(), Recorder.StopReason.QUIT, null);
                continue;
            }
            boolean ok;
            try {
                ok = r.tick(p);
            } catch (Throwable t) {
                Log.severe("record-tick-failed", t, "player", p.getName());
                ok = false;
            }
            if (!ok) {
                Throwable err = r.error();
                stop(r.uuid(), Recorder.StopReason.ERROR, null);
                this.plugin.notifyAdmins("record-error", "player", p.getName(), "error", err == null ? this.plugin.messages().plain("unknown-error") : Ref.rootMessage(err));
                continue;
            }
            // 依容量分段時扣掉開頭快照（新檔一開始就要寫一份完整快照），並且每段至少錄 1 分鐘，
            // 避免上限設得比快照還小時不停地切檔
            boolean split = (maxTicks > 0 && r.ticks() >= maxTicks)
                || (maxBytes > 0 && r.ticks() >= MIN_SPLIT_TICKS && r.contentBytes() >= maxBytes);
            if (split) {
                int part = this.parts.getOrDefault(r.uuid(), 1) + 1;
                stop(r.uuid(), Recorder.StopReason.SPLIT, null);
                if (!this.storageBlocked) {
                    this.pending.put(r.uuid(), new Pending(part, this.tickCounter + 20 * 60));
                    tryStartPending(p);
                    this.plugin.notifyAdmins("record-split", "player", p.getName(), "part", part);
                }
            }
        }
    }

    // ================================================================ 方塊效果

    public void onBlockBreak(Player player, org.bukkit.block.Block block) {
        Recorder r = this.active.get(player.getUniqueId());
        if (r != null) {
            r.onBlockBreak(block);
        }
    }

    public void onBlockPlace(Player player, org.bukkit.block.Block block) {
        Recorder r = this.active.get(player.getUniqueId());
        if (r != null) {
            r.onBlockPlace(block);
        }
    }

    // ================================================================ 容量

    /** 容量達到上限：所有錄製正確存檔後停止，並記下以便恢復。 */
    public List<String> blockForStorage() {
        this.storageBlocked = true;
        List<String> names = new ArrayList<>();
        for (Recorder r : new ArrayList<>(this.active.values())) {
            this.storageStopped.add(r.uuid());
            names.add(r.playerName());
            stop(r.uuid(), Recorder.StopReason.STORAGE, null);
        }
        for (UUID uuid : new ArrayList<>(this.pending.keySet())) {
            this.storageStopped.add(uuid);
            this.pending.remove(uuid);
        }
        return names;
    }

    /** 容量降回上限以下：恢復被停止的玩家（仍在線上者）。 */
    public List<String> unblockStorage() {
        this.storageBlocked = false;
        List<String> resumed = new ArrayList<>();
        for (UUID uuid : new ArrayList<>(this.storageStopped)) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && start(p) != StartResult.FAILED) {
                resumed.add(p.getName());
            }
        }
        this.storageStopped.clear();
        return resumed;
    }

    // ================================================================ 關服

    /** 停止所有錄製並等待存檔完成（關服時呼叫，會卡住直到完成）。 */
    public void shutdown() {
        this.pending.clear();
        List<Recorder> recorders = new ArrayList<>(this.active.values());
        if (!recorders.isEmpty()) {
            Log.info("shutdown-saving", "count", recorders.size());
        }
        for (Recorder r : recorders) {
            stop(r.uuid(), Recorder.StopReason.SHUTDOWN, null);
        }
        List<CompletableFuture<RecordingFile>> futures;
        synchronized (this.saving) {
            futures = new ArrayList<>(this.saving);
        }
        long start = System.currentTimeMillis();
        for (CompletableFuture<RecordingFile> f : futures) {
            while (true) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException e) {
                    Log.info("shutdown-waiting", "seconds", (System.currentTimeMillis() - start) / 1000);
                } catch (Exception e) {
                    break;
                }
            }
        }
        if (!futures.isEmpty()) {
            Log.info("shutdown-done");
        }
    }

    // ================================================================ 工具

    private static String worldName() {
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Bukkit.getServer().motd());
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static int dataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static int protocolVersion() {
        try {
            return (int) Bukkit.getUnsafe().getClass().getMethod("getProtocolVersion").invoke(Bukkit.getUnsafe());
        } catch (Throwable t) {
            return 0;
        }
    }
}
