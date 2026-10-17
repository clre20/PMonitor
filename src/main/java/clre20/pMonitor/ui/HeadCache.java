package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家頭顱：線上玩家直接用目前的外觀；離線玩家在背景向 Mojang 取得外觀（每位玩家最多查一次），
 * 取得後會刷新已開啟的介面，不會卡住伺服器。
 * <p>
 * 注意：Paper 在頭顱物品套用「沒有外觀資料」的 profile 時會自己向 Mojang 查詢，
 * 所以只有在確定有外觀資料時才套用 profile，否則用預設頭顱，避免每次開介面都連線。
 */
public final class HeadCache {

    private final PMonitor plugin;
    private final Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();

    public HeadCache(PMonitor plugin) {
        this.plugin = plugin;
    }

    public ItemStack head(UUID uuid, String name) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        PlayerProfile profile = null;
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            PlayerProfile p = online.getPlayerProfile();
            if (p.hasTextures()) {
                this.profiles.put(uuid, p);
                profile = p;
            }
        }
        if (profile == null) {
            profile = this.profiles.get(uuid);
        }
        if (profile == null) {
            load(uuid, name);
            return item;
        }
        try {
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 名稱不合法等情況：使用預設頭顱
        }
        return item;
    }

    /** 基岩版（Geyser／Floodgate）玩家的 UUID 前半段為 0，Mojang 查不到，不要查。 */
    static boolean isBedrock(UUID uuid) {
        return uuid.getMostSignificantBits() == 0L;
    }

    private void load(UUID uuid, String name) {
        if (isBedrock(uuid) || name == null || name.isEmpty() || this.failed.contains(uuid) || !this.loading.add(uuid)) {
            return;
        }
        if (uuid.version() != 4) {
            // 正版 UUID 是第 4 版；離線模式（第 3 版）的 UUID 查了也沒用
            this.failed.add(uuid);
            this.loading.remove(uuid);
            return;
        }
        PlayerProfile profile = Bukkit.createProfile(uuid, name);
        profile.update().whenComplete((updated, error) -> {
            this.loading.remove(uuid);
            if (error != null || updated == null || !updated.hasTextures()) {
                // 查不到外觀或連線逾時：本次啟動期間不再重試，使用預設頭顱
                this.failed.add(uuid);
            }
            if (error == null && updated != null && updated.hasTextures()) {
                this.profiles.put(uuid, updated);
                if (this.plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(this.plugin, () -> this.plugin.menus().refreshOpen());
                }
            }
        });
    }
}
