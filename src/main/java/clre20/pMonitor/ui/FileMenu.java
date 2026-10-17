package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** 檔案頁：下載連結（或後台路徑）、刪除。 */
public final class FileMenu extends Menu {

    private final String name;

    public FileMenu(PMonitor plugin, Player viewer, Menu parent, String name) {
        super(plugin, viewer, parent, 3, plugin.messages().component("gui.title.file", "file", name));
        this.name = name;
    }

    @Override
    public void render() {
        this.inventory.clear();
        for (int i = 0; i < 27; i++) {
            this.inventory.setItem(i, filler());
        }
        FileEntry e = FileEntry.find(this.plugin, this.name);
        if (e == null) {
            this.inventory.setItem(13, item(Material.BARRIER, "gui.file-missing", null));
            this.inventory.setItem(22, item(Material.ARROW, "gui.back", null));
            return;
        }
        Object[] ph = e.placeholders(msg());
        this.inventory.setItem(4, item(e.recording() ? Material.CLOCK : Material.PAPER, "gui.file.name", "gui.file.lore", ph));
        if (e.recording()) {
            this.inventory.setItem(11, item(Material.GRAY_DYE, "gui.download-locked.name", "gui.download-locked.lore", ph));
            this.inventory.setItem(15, item(Material.GRAY_DYE, "gui.delete-locked.name", "gui.delete-locked.lore", ph));
        } else {
            if (this.plugin.config().downloadEnabled) {
                this.inventory.setItem(11, item(Material.LIME_DYE, "gui.download.name", "gui.download.lore", ph));
            } else {
                this.inventory.setItem(11, item(Material.FILLED_MAP, "gui.location.name", "gui.location.lore",
                    "path", FileActions.displayPath(e.path())));
            }
            this.inventory.setItem(15, item(Material.LAVA_BUCKET, "gui.delete.name", "gui.delete.lore", ph));
        }
        this.inventory.setItem(22, item(Material.ARROW, "gui.back", null));
    }

    @Override
    public void click(int slot) {
        FileEntry e = FileEntry.find(this.plugin, this.name);
        switch (slot) {
            case 11 -> {
                if (e == null || e.recording()) {
                    playFail();
                    return;
                }
                // 下載關閉時只是顯示後台路徑，有查看列表的權限即可
                if (!require(this.plugin.config().downloadEnabled ? "pmonitor.download" : "pmonitor.list")) {
                    return;
                }
                playClick();
                this.viewer.closeInventory();
                FileActions.sendDownload(this.plugin, this.viewer, e);
            }
            case 15 -> {
                if (e == null || e.recording()) {
                    playFail();
                    if (e != null) {
                        msg().send(this.viewer, "delete-recording");
                    }
                    return;
                }
                if (require("pmonitor.delete")) {
                    playClick();
                    new ConfirmDeleteMenu(this.plugin, this.viewer, this, this.name).open();
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
}
