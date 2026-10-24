package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 箱子介面的基底：全部左鍵操作，關閉（Esc）回到上一層。
 * <p>
 * 插件簽署：所有 PMonitor 介面的 InventoryHolder 都是這個類別，其他插件可用
 * {@code inventory.getHolder(false) instanceof Menu} 或 {@link #owningPlugin()} 辨識來源。
 */
public abstract class Menu implements InventoryHolder {

    /** 這個介面所屬的插件（插件簽署）。 */
    public final org.bukkit.plugin.Plugin owningPlugin() {
        return this.plugin;
    }

    protected final PMonitor plugin;
    protected final Player viewer;
    protected final Menu parent;
    protected final Inventory inventory;

    protected Menu(PMonitor plugin, Player viewer, Menu parent, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.parent = parent;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    /** 畫出所有物品（每秒也會重畫一次以更新時間）。 */
    public abstract void render();

    /** 左鍵點擊上方介面的格子。 */
    public abstract void click(int slot);

    public void open() {
        render();
        this.viewer.openInventory(this.inventory);
    }

    /** 回上一層；沒有上一層則關閉。 */
    public void back() {
        if (this.parent != null) {
            this.parent.open();
        } else {
            this.viewer.closeInventory();
        }
    }

    public Menu parent() {
        return this.parent;
    }

    protected Messages msg() {
        return this.plugin.messages();
    }

    protected ItemStack item(Material material, String nameKey, String loreKey, Object... args) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(msg().item(nameKey, args));
        if (loreKey != null) {
            meta.lore(msg().itemLore(loreKey, args));
        }
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    protected ItemStack named(ItemStack item, Component name, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    protected ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    protected void fillRow(int row) {
        for (int i = row * 9; i < row * 9 + 9; i++) {
            this.inventory.setItem(i, filler());
        }
    }

    protected void playClick() {
        this.plugin.menus().sound(this.viewer, this.plugin.config().soundClick);
    }

    protected void playSuccess() {
        this.plugin.menus().sound(this.viewer, this.plugin.config().soundSuccess);
    }

    protected void playFail() {
        this.plugin.menus().sound(this.viewer, this.plugin.config().soundFail);
    }

    protected boolean require(String permission) {
        if (this.viewer.hasPermission(permission)) {
            return true;
        }
        playFail();
        msg().send(this.viewer, "no-permission");
        return false;
    }
}
