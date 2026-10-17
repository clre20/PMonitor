package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.data.PlayerDataStore;
import clre20.pMonitor.record.Recorder;
import clre20.pMonitor.record.RecordingManager;
import clre20.pMonitor.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/** 玩家頁：開始／停止錄製、下次進服錄製、每次進服自動錄製、錄製檔列表。 */
public final class PlayerMenu extends Menu {

    private final UUID uuid;
    private final String name;

    public PlayerMenu(PMonitor plugin, Player viewer, Menu parent, UUID uuid, String name) {
        super(plugin, viewer, parent, 3, plugin.messages().component("gui.title.player", "player", name));
        this.uuid = uuid;
        this.name = name;
    }

    @Override
    public void render() {
        this.inventory.clear();
        for (int i = 0; i < 27; i++) {
            this.inventory.setItem(i, filler());
        }
        Recorder r = this.plugin.recordings().recorder(this.uuid);
        Player online = Bukkit.getPlayer(this.uuid);
        String duration = r == null ? "-" : Fmt.duration(System.currentTimeMillis() - r.startMillis());
        String size = r == null ? "-" : Fmt.size(r.bytesWritten());
        this.inventory.setItem(4, named(this.plugin.heads().head(this.uuid, this.name),
            msg().item(online != null ? "gui.player.name-online" : "gui.player.name-offline", "player", this.name),
            msg().itemLore("gui.player-info.lore",
                "status", MainMenu.statusLine(this.plugin, this.uuid),
                "duration", duration, "size", size,
                "online", msg().raw(online != null ? "gui.online-now" : "gui.offline"))));

        PlayerDataStore.Entry data = this.plugin.playerData().get(this.uuid);
        boolean armed = data != null && data.armed;
        boolean auto = data != null && data.autoRecord;
        if (online != null) {
            if (r != null) {
                this.inventory.setItem(11, item(Material.RED_CONCRETE, "gui.stop.name", "gui.stop.lore", "duration", duration));
            } else if (this.plugin.recordings().isWaiting(this.uuid)) {
                this.inventory.setItem(11, item(Material.YELLOW_CONCRETE, "gui.waiting.name", "gui.waiting.lore"));
            } else {
                this.inventory.setItem(11, item(Material.LIME_CONCRETE, "gui.start.name", "gui.start.lore"));
            }
        } else {
            this.inventory.setItem(11, item(armed ? Material.LIME_DYE : Material.GRAY_DYE, "gui.arm.name", "gui.arm.lore",
                "state", msg().raw(armed ? "gui.enabled" : "gui.disabled")));
        }
        this.inventory.setItem(13, item(auto ? Material.REPEATER : Material.COMPARATOR, "gui.auto.name", "gui.auto.lore",
            "state", msg().raw(auto ? "gui.enabled" : "gui.disabled")));
        this.inventory.setItem(15, item(Material.BOOK, "gui.player-files.name", "gui.player-files.lore",
            "count", this.plugin.index().count(this.uuid) + (r != null ? 1 : 0)));
        this.inventory.setItem(22, item(Material.ARROW, "gui.back", null));
    }

    @Override
    public void click(int slot) {
        switch (slot) {
            case 11 -> toggleMain();
            case 13 -> {
                if (!require("pmonitor.start")) {
                    return;
                }
                PlayerDataStore.Entry data = this.plugin.playerData().getOrCreate(this.uuid, this.name);
                data.autoRecord = !data.autoRecord;
                this.plugin.playerData().save(data);
                playSuccess();
                msg().send(this.viewer, data.autoRecord ? "auto-on" : "auto-off", "player", this.name);
                render();
            }
            case 15 -> {
                if (require("pmonitor.list")) {
                    playClick();
                    new FileListMenu(this.plugin, this.viewer, this, this.uuid, this.name).open();
                }
            }
            case 22 -> {
                playClick();
                back();
            }
            default -> {
            }
        }
    }

    private void toggleMain() {
        Player online = Bukkit.getPlayer(this.uuid);
        RecordingManager rm = this.plugin.recordings();
        if (rm.isRecording(this.uuid)) {
            if (!require("pmonitor.stop")) {
                return;
            }
            boolean wasActive = rm.recorder(this.uuid) != null;
            rm.stop(this.uuid, Recorder.StopReason.MANUAL, file -> {
                if (file != null && this.viewer.isOnline()) {
                    msg().send(this.viewer, "record-saved", "player", this.name, "file", file.baseName());
                }
            });
            playSuccess();
            msg().send(this.viewer, wasActive ? "record-stopped" : "record-cancel-waiting", "player", this.name);
            render();
            return;
        }
        if (online == null) {
            if (!require(isArmed() ? "pmonitor.stop" : "pmonitor.start")) {
                return;
            }
            if (isArmed()) {
                rm.disarm(this.uuid);
                msg().send(this.viewer, "record-disarmed", "player", this.name);
            } else {
                rm.arm(this.uuid, this.name);
                msg().send(this.viewer, "record-armed", "player", this.name);
            }
            playSuccess();
            render();
            return;
        }
        if (!require("pmonitor.start")) {
            return;
        }
        RecordingManager.StartResult result = rm.start(online);
        switch (result) {
            case STARTED -> {
                playSuccess();
                msg().send(this.viewer, "record-started", "player", this.name);
            }
            case WAITING -> {
                playSuccess();
                msg().send(this.viewer, "record-waiting", "player", this.name);
            }
            case BLOCKED -> {
                playFail();
                msg().send(this.viewer, "record-blocked-storage", "used", Fmt.size(this.plugin.storage().usedBytes()),
                    "limit", Fmt.size(this.plugin.config().stopSizeBytes));
            }
            case UNAVAILABLE -> {
                playFail();
                msg().send(this.viewer, "record-unavailable", "reason", String.valueOf(this.plugin.nmsError()));
            }
            default -> playFail();
        }
        render();
    }

    private boolean isArmed() {
        PlayerDataStore.Entry data = this.plugin.playerData().get(this.uuid);
        return data != null && data.armed;
    }
}
