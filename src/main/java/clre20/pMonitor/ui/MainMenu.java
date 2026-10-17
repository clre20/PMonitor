package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.data.PlayerDataStore;
import clre20.pMonitor.record.Recorder;
import clre20.pMonitor.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 主介面：玩家列表（含離線），點選進入玩家頁。 */
public final class MainMenu extends Menu {

    enum Filter { ALL, ONLINE, RECORDING }

    record Entry(UUID uuid, String name, boolean online, long lastSeen) {
    }

    private static final int PAGE_SIZE = 45;

    private int page;
    private Filter filter = Filter.ALL;
    private String search;
    private List<Entry> entries = List.of();
    private long entriesBuilt;

    public MainMenu(PMonitor plugin, Player viewer) {
        super(plugin, viewer, null, 6, plugin.messages().component("gui.title.main"));
    }

    private void rebuild() {
        Map<UUID, Entry> map = new HashMap<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null) {
                map.put(op.getUniqueId(), new Entry(op.getUniqueId(), op.getName(), false, op.getLastSeen()));
            }
        }
        for (PlayerDataStore.Entry e : this.plugin.playerData().all()) {
            Entry old = map.get(e.uuid);
            map.put(e.uuid, new Entry(e.uuid, e.name, false, Math.max(e.lastSeen, old == null ? 0 : old.lastSeen)));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            map.put(p.getUniqueId(), new Entry(p.getUniqueId(), p.getName(), true, System.currentTimeMillis()));
        }
        List<Entry> list = new ArrayList<>();
        for (Entry e : map.values()) {
            if (this.filter == Filter.ONLINE && !e.online) {
                continue;
            }
            if (this.filter == Filter.RECORDING && !this.plugin.recordings().isRecording(e.uuid)) {
                continue;
            }
            if (this.search != null && !e.name.toLowerCase(Locale.ROOT).contains(this.search.toLowerCase(Locale.ROOT))) {
                continue;
            }
            list.add(e);
        }
        list.sort((a, b) -> {
            if (a.online != b.online) {
                return a.online ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        this.entries = list;
        this.entriesBuilt = System.currentTimeMillis();
        int maxPage = Math.max(0, (list.size() - 1) / PAGE_SIZE);
        this.page = Math.min(this.page, maxPage);
    }

    @Override
    public void render() {
        if (System.currentTimeMillis() - this.entriesBuilt > 3000) {
            rebuild();
        }
        this.inventory.clear();
        int start = this.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < this.entries.size(); i++) {
            Entry e = this.entries.get(start + i);
            this.inventory.setItem(i, headFor(e));
        }
        if (this.entries.isEmpty()) {
            this.inventory.setItem(22, item(Material.BARRIER, "gui.empty-players", null));
        }

        fillRow(5);
        int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (this.page > 0) {
            this.inventory.setItem(45, item(Material.ARROW, "gui.prev", "gui.page-lore", "page", this.page + 1, "pages", pages));
        }
        if (this.page < pages - 1) {
            this.inventory.setItem(53, item(Material.ARROW, "gui.next", "gui.page-lore", "page", this.page + 1, "pages", pages));
        }
        this.inventory.setItem(46, item(Material.HOPPER, "gui.filter.name", "gui.filter.lore",
            "filter", msg().raw("gui.filter." + this.filter.name().toLowerCase(Locale.ROOT))));
        if (this.search == null) {
            this.inventory.setItem(47, item(Material.OAK_SIGN, "gui.search.name", "gui.search.lore"));
        } else {
            this.inventory.setItem(47, item(Material.OAK_SIGN, "gui.search.name", "gui.search.lore-active", "search", this.search));
        }
        long used = this.plugin.storage().usedBytes();
        var c = this.plugin.config();
        if (this.plugin.recordings().isStorageBlocked()) {
            this.inventory.setItem(48, item(Material.RED_CONCRETE, "gui.storage.stop-name", "gui.storage.lore",
                "used", Fmt.size(used), "warn", sizeOrNone(c.warnSizeBytes), "stop", sizeOrNone(c.stopSizeBytes)));
        } else if (c.warnSizeBytes > 0 && used >= c.warnSizeBytes) {
            this.inventory.setItem(48, item(Material.YELLOW_CONCRETE, "gui.storage.warn-name", "gui.storage.lore",
                "used", Fmt.size(used), "warn", sizeOrNone(c.warnSizeBytes), "stop", sizeOrNone(c.stopSizeBytes)));
        }
        this.inventory.setItem(49, item(Material.CHEST, "gui.all-files.name", "gui.all-files.lore",
            "count", this.plugin.index().all().size() + this.plugin.recordings().activeRecorders().size(), "size", Fmt.size(used)));
        this.inventory.setItem(51, item(Material.BARRIER, "gui.close", null));
    }

    private String sizeOrNone(long bytes) {
        return bytes > 0 ? Fmt.size(bytes) : msg().raw("storage-no-limit");
    }

    private ItemStack headFor(Entry e) {
        ItemStack head = this.plugin.heads().head(e.uuid, e.name);
        PlayerDataStore.Entry data = this.plugin.playerData().get(e.uuid);
        boolean auto = data != null && data.autoRecord;
        String lastSeen = e.online ? msg().raw("gui.online-now") : (e.lastSeen > 0 ? Fmt.dateTime(e.lastSeen) : "-");
        return named(head,
            msg().item(e.online ? "gui.player.name-online" : "gui.player.name-offline", "player", e.name),
            msg().itemLore("gui.player.lore",
                "status", statusLine(this.plugin, e.uuid),
                "auto", msg().raw(auto ? "gui.enabled" : "gui.disabled"),
                "files", this.plugin.index().count(e.uuid),
                "last-seen", lastSeen));
    }

    /** 玩家的錄製狀態文字。 */
    static String statusLine(PMonitor plugin, UUID uuid) {
        Recorder r = plugin.recordings().recorder(uuid);
        if (r != null) {
            return plugin.messages().raw("gui.status.recording", "duration", Fmt.duration(System.currentTimeMillis() - r.startMillis()));
        }
        if (plugin.recordings().isWaiting(uuid)) {
            return plugin.messages().raw("gui.status.waiting");
        }
        PlayerDataStore.Entry data = plugin.playerData().get(uuid);
        if (data != null && data.armed) {
            return plugin.messages().raw("gui.status.armed");
        }
        return plugin.messages().raw("gui.status.idle");
    }

    @Override
    public void click(int slot) {
        if (slot < PAGE_SIZE) {
            int index = this.page * PAGE_SIZE + slot;
            if (index < this.entries.size()) {
                playClick();
                Entry e = this.entries.get(index);
                new PlayerMenu(this.plugin, this.viewer, this, e.uuid, e.name).open();
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                if (this.page > 0) {
                    this.page--;
                    playClick();
                    render();
                }
            }
            case 53 -> {
                if ((this.page + 1) * PAGE_SIZE < this.entries.size()) {
                    this.page++;
                    playClick();
                    render();
                }
            }
            case 46 -> {
                this.filter = Filter.values()[(this.filter.ordinal() + 1) % Filter.values().length];
                this.page = 0;
                playClick();
                rebuild();
                render();
            }
            case 47 -> {
                playClick();
                if (this.search != null) {
                    this.search = null;
                    this.page = 0;
                    rebuild();
                    render();
                    return;
                }
                this.plugin.chatInput().request(this.viewer, "input-prompt-search", text -> {
                    this.search = text;
                    this.page = 0;
                    rebuild();
                    open();
                }, this::open);
            }
            case 49 -> {
                if (require("pmonitor.list")) {
                    playClick();
                    new FileListMenu(this.plugin, this.viewer, this, null, null).open();
                }
            }
            case 51 -> {
                playClick();
                this.viewer.closeInventory();
            }
            default -> {
            }
        }
    }
}
