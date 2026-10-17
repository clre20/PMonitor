package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** 刪除確認。 */
public final class ConfirmDeleteMenu extends Menu {

    private final String name;

    public ConfirmDeleteMenu(PMonitor plugin, Player viewer, Menu parent, String name) {
        super(plugin, viewer, parent, 3, plugin.messages().component("gui.title.confirm"));
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
            return;
        }
        Object[] ph = e.placeholders(msg());
        this.inventory.setItem(11, item(Material.RED_CONCRETE, "gui.confirm.confirm", "gui.confirm.confirm-lore", ph));
        this.inventory.setItem(13, item(Material.PAPER, "gui.file.name", "gui.file.lore", ph));
        this.inventory.setItem(15, item(Material.LIME_CONCRETE, "gui.confirm.cancel", null));
    }

    @Override
    public void click(int slot) {
        switch (slot) {
            case 11 -> {
                if (!require("pmonitor.delete")) {
                    return;
                }
                FileEntry e = FileEntry.find(this.plugin, this.name);
                if (e == null || e.recording()) {
                    playFail();
                    back();
                    return;
                }
                if (FileActions.delete(this.plugin, this.viewer, e)) {
                    playSuccess();
                    // 回到檔案列表（跳過已不存在的檔案頁）
                    Menu list = this.parent != null && this.parent.parent() != null ? this.parent.parent() : null;
                    if (list != null) {
                        list.open();
                    } else {
                        this.viewer.closeInventory();
                    }
                } else {
                    playFail();
                    back();
                }
            }
            case 15 -> {
                playClick();
                back();
            }
            default -> {
            }
        }
    }
}
