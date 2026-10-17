package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** 介面事件：一律取消物品移動，只處理左鍵；Esc 關閉時回到上一層。 */
public final class Menus implements Listener {

    private final PMonitor plugin;

    public Menus(PMonitor plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player player) {
        new MainMenu(this.plugin, player).open();
    }

    public void sound(Player player, Sound sound) {
        if (this.plugin.config().soundsEnabled && sound != null) {
            player.playSound(player.getLocation(), sound, 0.6f, 1.0f);
        }
    }

    /** 每秒重畫開著的介面（更新錄製時間、頭顱外觀等）。 */
    public void refreshOpen() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder(false) instanceof Menu menu) {
                try {
                    menu.render();
                } catch (Throwable t) {
                    this.plugin.getLogger().warning("更新介面失敗：" + t.getMessage());
                }
            }
        }
    }

    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                p.closeInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        // 任何點擊都不能拿走或放入物品
        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT) {
            return;
        }
        if (event.getClickedInventory() != top) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (event.getWhoClicked() instanceof Player p && p.getOpenInventory().getTopInventory() == top) {
                try {
                    menu.click(slot);
                } catch (Throwable t) {
                    this.plugin.getLogger().warning("處理介面點擊失敗：" + t);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        if (event.getReason() != InventoryCloseEvent.Reason.PLAYER) {
            return;
        }
        Player player = (Player) event.getPlayer();
        if (menu.parent() == null || this.plugin.chatInput().isWaiting(player)) {
            return;
        }
        // Esc = 回上一層
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline() && !(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu)) {
                menu.parent().open();
            }
        });
    }
}
