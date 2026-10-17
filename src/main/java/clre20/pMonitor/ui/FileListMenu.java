package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.record.RecordingFile;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** 錄製檔列表：每個檔案一張紙，錄製中的排最前面，其餘新到舊。 */
public final class FileListMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final UUID uuid;
    private int page;
    private List<FileEntry> entries = List.of();

    public FileListMenu(PMonitor plugin, Player viewer, Menu parent, UUID uuid, String name) {
        super(plugin, viewer, parent, 6, uuid == null
            ? plugin.messages().component("gui.title.files-all")
            : plugin.messages().component("gui.title.files", "player", name));
        this.uuid = uuid;
    }

    @Override
    public void render() {
        this.entries = FileEntry.list(this.plugin, this.uuid);
        int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Math.min(this.page, pages - 1);
        this.inventory.clear();
        int start = this.page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < this.entries.size(); i++) {
            FileEntry e = this.entries.get(start + i);
            Material m = e.recording() ? Material.CLOCK
                : e.status() == RecordingFile.Status.INTERRUPTED ? Material.MAP : Material.PAPER;
            this.inventory.setItem(i, item(m, "gui.file.name", "gui.file.lore", e.placeholders(msg())));
        }
        if (this.entries.isEmpty()) {
            this.inventory.setItem(22, item(Material.BARRIER, "gui.empty-files", null));
        }
        fillRow(5);
        if (this.page > 0) {
            this.inventory.setItem(45, item(Material.ARROW, "gui.prev", "gui.page-lore", "page", this.page + 1, "pages", pages));
        }
        if (this.page < pages - 1) {
            this.inventory.setItem(53, item(Material.ARROW, "gui.next", "gui.page-lore", "page", this.page + 1, "pages", pages));
        }
        this.inventory.setItem(49, item(Material.ARROW, "gui.back", null));
    }

    @Override
    public void click(int slot) {
        if (slot < PAGE_SIZE) {
            int index = this.page * PAGE_SIZE + slot;
            if (index < this.entries.size()) {
                playClick();
                new FileMenu(this.plugin, this.viewer, this, this.entries.get(index).name()).open();
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
            case 49 -> {
                playClick();
                back();
            }
            default -> {
            }
        }
    }
}
