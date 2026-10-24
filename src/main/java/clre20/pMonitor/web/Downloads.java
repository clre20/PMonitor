package clre20.pMonitor.web;

import clre20.pMonitor.PMonitor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/**
 * 錄製檔下載（透過 HttpHub 共用端口）。
 * <p>
 * 這個類別不直接使用 HttpHub 的類別：只有確認 HttpHub 已啟用後才建立 {@link HubDownloads}，
 * 沒裝 HttpHub 時不會出現 NoClassDefFoundError。
 */
public final class Downloads {

    /** 建立連結的結果；url 只在 status 為 OK 時有值。 */
    public record Link(Status status, String url) {
    }

    public enum Status { OK, DISABLED, NO_HUB, NOT_RUNNING, NO_ADDRESS }

    /** 由 {@link HubDownloads} 實作，讓這裡不需要引用 HttpHub 的類別。 */
    interface Bridge {
        boolean running();

        /** @return 網址；取不到伺服器位址時回傳 null */
        String create(Path file, Player viewer);

        void clear();
    }

    private final PMonitor plugin;
    private Bridge bridge;

    public Downloads(PMonitor plugin) {
        this.plugin = plugin;
    }

    public static boolean hubInstalled() {
        Plugin hub = Bukkit.getPluginManager().getPlugin("HttpHub");
        return hub != null && hub.isEnabled();
    }

    /** 建立下載連結（主執行緒）。 */
    public Link createLink(Path file, CommandSender sender) {
        if (!this.plugin.config().downloadEnabled) {
            return new Link(Status.DISABLED, null);
        }
        if (!hubInstalled()) {
            return new Link(Status.NO_HUB, null);
        }
        if (this.bridge == null) {
            this.bridge = new HubDownloads(this.plugin);
        }
        if (!this.bridge.running()) {
            return new Link(Status.NOT_RUNNING, null);
        }
        String url = this.bridge.create(file, sender instanceof Player p ? p : null);
        return url == null ? new Link(Status.NO_ADDRESS, null) : new Link(Status.OK, url);
    }

    /** 重新載入設定後：關閉下載時清掉所有連結並移除路徑。 */
    public void onReload() {
        if (!this.plugin.config().downloadEnabled) {
            clear();
        }
    }

    public void clear() {
        if (this.bridge != null) {
            try {
                this.bridge.clear();
            } catch (Throwable ignored) {
                // HttpHub 已經不在：路徑會由 HttpHub 自己移除
            }
            this.bridge = null;
        }
    }
}
