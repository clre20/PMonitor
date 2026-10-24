package clre20.pMonitor.data;

import clre20.pMonitor.util.Log;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** playerdata/&lt;UUID&gt;.yml：玩家名稱、最後上線時間、待命與每次進服自動錄製設定。 */
public final class PlayerDataStore {

    public static final class Entry {
        public final UUID uuid;
        public volatile String name;
        public volatile long lastSeen;
        public volatile boolean autoRecord;
        public volatile boolean armed;

        Entry(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private final JavaPlugin plugin;
    private final File dir;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PMonitor-PlayerData");
        t.setDaemon(true);
        return t;
    });

    public PlayerDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "playerdata");
    }

    public void load() {
        this.entries.clear();
        if (!this.dir.isDirectory() && !this.dir.mkdirs()) {
            return;
        }
        File[] files = this.dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File f : files) {
            try {
                UUID uuid = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                Entry e = new Entry(uuid, y.getString("name", uuid.toString().substring(0, 8)));
                e.lastSeen = y.getLong("last-seen-epoch", 0);
                e.autoRecord = y.getBoolean("auto-record", false);
                e.armed = y.getBoolean("armed", false);
                this.entries.put(uuid, e);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public Entry get(UUID uuid) {
        return this.entries.get(uuid);
    }

    public Entry getOrCreate(UUID uuid, String name) {
        return this.entries.computeIfAbsent(uuid, u -> new Entry(u, name));
    }

    public Collection<Entry> all() {
        return new ArrayList<>(this.entries.values());
    }

    public List<Entry> armedOrAuto() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : this.entries.values()) {
            if (e.armed || e.autoRecord) {
                out.add(e);
            }
        }
        return out;
    }

    /** 背景寫入檔案。 */
    public void save(Entry e) {
        String name = e.name;
        long lastSeen = e.lastSeen;
        boolean auto = e.autoRecord;
        boolean armed = e.armed;
        this.io.execute(() -> write(e.uuid, name, lastSeen, auto, armed));
    }

    private void write(UUID uuid, String name, long lastSeen, boolean auto, boolean armed) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("PMonitor 玩家資料（由插件自動維護）"));
        y.set("name", name);
        y.set("last-seen", lastSeen == 0 ? "" : java.time.Instant.ofEpochMilli(lastSeen).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString());
        y.set("last-seen-epoch", lastSeen);
        y.set("auto-record", auto);
        y.set("armed", armed);
        try {
            if (!this.dir.isDirectory()) {
                this.dir.mkdirs();
            }
            y.save(new File(this.dir, uuid + ".yml"));
        } catch (IOException ex) {
            Log.warn("playerdata-save-failed", "error", ex.getMessage());
        }
    }

    public void shutdown() {
        this.io.shutdown();
        try {
            this.io.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
