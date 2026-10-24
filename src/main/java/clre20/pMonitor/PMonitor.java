package clre20.pMonitor;

import clre20.pMonitor.command.PMonitorCommand;
import clre20.pMonitor.config.ConfigFiles;
import clre20.pMonitor.config.Messages;
import clre20.pMonitor.config.PluginConfig;
import clre20.pMonitor.data.PlayerDataStore;
import clre20.pMonitor.net.TapManager;
import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.record.RecordingManager;
import clre20.pMonitor.record.Recovery;
import clre20.pMonitor.storage.RecordingIndex;
import clre20.pMonitor.storage.StorageMonitor;
import clre20.pMonitor.ui.ChatInput;
import clre20.pMonitor.ui.HeadCache;
import clre20.pMonitor.ui.Menus;
import clre20.pMonitor.update.UpdateChecker;
import clre20.pMonitor.update.UpdateNotifier;
import clre20.pMonitor.util.Log;
import clre20.pMonitor.web.Downloads;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class PMonitor extends JavaPlugin implements Listener {

    /** 已測試的 Minecraft 版本（Paper）。 */
    private static final Set<String> TESTED = Set.of("1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    private PluginConfig config;
    private Messages messages;
    private PlayerDataStore playerData;
    private RecordingIndex index;
    private RecordingManager recordings;
    private StorageMonitor storage;
    private TapManager taps;
    private UpdateChecker updates;
    private UpdateNotifier updateNotifier;
    private Downloads download;
    private Menus menus;
    private ChatInput chatInput;
    private HeadCache heads;
    private String nmsError;

    @Override
    public void onEnable() {
        Log.init(this);
        loadConfigs();

        String mc = Bukkit.getMinecraftVersion();
        if (!TESTED.contains(mc)) {
            Log.warn("untested-version", "version", mc);
        }

        this.nmsError = Nms.init();
        if (this.nmsError != null) {
            Log.severe("nms-failed", null, "error", this.nmsError);
        }

        Path recordingsDir = getDataFolder().toPath().resolve("recordings");
        this.playerData = new PlayerDataStore(this);
        this.playerData.load();
        this.index = new RecordingIndex(recordingsDir, getLogger());
        this.recordings = new RecordingManager(this, recordingsDir);
        this.storage = new StorageMonitor(this, recordingsDir);
        this.taps = new TapManager(getLogger());
        this.updates = new UpdateChecker(getPluginMeta().getVersion());
        this.updateNotifier = new UpdateNotifier(this);
        this.download = new Downloads(this);
        this.heads = new HeadCache(this);
        this.chatInput = new ChatInput(this);
        this.menus = new Menus(this);

        if (Nms.ready()) {
            this.taps.start();
        }

        // 修復上次沒有正常結束的錄製，再建立錄製檔清單
        // （先在這裡列出，避免把之後新開始的錄製當成未完成）
        List<Path> unfinished = Recovery.findUnfinished(recordingsDir);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (Path work : unfinished) {
                Log.info("recover-start", "file", work.getFileName());
                Path zip = Recovery.recover(work, getLogger());
                if (zip != null) {
                    Log.info("recover-done", "file", zip.getFileName());
                }
            }
            this.index.scan();
            this.storage.requestCheck();
            this.storage.cleanupOld();
        });

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(this.menus, this);
        getServer().getPluginManager().registerEvents(this.chatInput, this);
        getServer().getPluginManager().registerEvents(this.updateNotifier, this);

        PluginCommand command = getCommand("pmonitor");
        if (command != null) {
            PMonitorCommand executor = new PMonitorCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // 插件中途載入（例如 /reload）：已在線上的玩家
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (Nms.ready()) {
                this.taps.bind(p);
            }
            this.recordings.onJoin(p);
        }

        Bukkit.getScheduler().runTaskTimer(this, this.recordings::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this.storage::requestCheck, 20L * 60, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(this, this.storage::cleanupOld, 20L * 3600, 20L * 3600);
        Bukkit.getScheduler().runTaskTimer(this, this.menus::refreshOpen, 20L, 20L);

        this.updateNotifier.checkOnStartup();
        Log.info("enabled", "version", getPluginMeta().getVersion(),
            "hub", this.messages.plain(Downloads.hubInstalled() ? "console.hub-found" : "console.hub-missing"));
    }

    @Override
    public void onDisable() {
        // 關服時必須等所有錄製存檔完成
        if (this.recordings != null) {
            this.recordings.shutdown();
        }
        if (this.menus != null) {
            this.menus.closeAll();
        }
        if (this.taps != null && Nms.ready()) {
            this.taps.stop(Bukkit.getOnlinePlayers());
        }
        if (this.download != null) {
            this.download.clear();
        }
        if (this.updates != null) {
            this.updates.shutdown();
        }
        if (this.playerData != null) {
            this.playerData.shutdown();
        }
        Log.clear();
    }

    public void loadConfigs() {
        this.config = new PluginConfig(ConfigFiles.load(this, "config.yml"));
        this.messages = new Messages(ConfigFiles.load(this, "messages.yml"), this.config.prefix);
    }

    /** /cpm reload */
    public void reload() {
        loadConfigs();
        this.download.onReload();
        this.storage.onReload();
    }

    // ================================================================ 事件

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (Nms.ready()) {
            this.taps.bind(player);
        }
        this.recordings.onJoin(player);
        this.storage.onJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.recordings.onQuit(player);
        this.taps.forget(player.getUniqueId());
        this.chatInput.cancel(player);
    }

    /** /minecraft:reload 等重新載入資料包後，快照用的註冊表要重新產生。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcesReloaded(io.papermc.paper.event.server.ServerResourcesReloadedEvent event) {
        if (Nms.ready()) {
            Nms.get().clearConfigurationCache();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (this.config.recordOwnBlockEffects) {
            this.recordings.onBlockBreak(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (this.config.recordOwnBlockEffects) {
            this.recordings.onBlockPlace(event.getPlayer(), event.getBlockPlaced());
        }
    }

    /** 通知管理員（主控台 + 有權限的線上玩家）。 */
    public void notifyAdmins(String key, Object... args) {
        getLogger().info(this.messages.plain(key, args));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("pmonitor.status")) {
                this.messages.send(p, key, args);
            }
        }
    }

    // ================================================================ 存取

    public PluginConfig config() {
        return this.config;
    }

    public Messages messages() {
        return this.messages;
    }

    public PlayerDataStore playerData() {
        return this.playerData;
    }

    public RecordingIndex index() {
        return this.index;
    }

    public RecordingManager recordings() {
        return this.recordings;
    }

    public StorageMonitor storage() {
        return this.storage;
    }

    public TapManager taps() {
        return this.taps;
    }

    public UpdateChecker updates() {
        return this.updates;
    }

    public UpdateNotifier updateNotifier() {
        return this.updateNotifier;
    }

    public Downloads download() {
        return this.download;
    }

    public Menus menus() {
        return this.menus;
    }

    public ChatInput chatInput() {
        return this.chatInput;
    }

    public HeadCache heads() {
        return this.heads;
    }

    public String nmsError() {
        return this.nmsError;
    }

    public List<String> testedVersions() {
        return List.of("1.21.11", "26.1", "26.2", "26.3");
    }
}
