package clre20.pMonitor.update;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.util.Log;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/** 版本檢查結果的顯示（啟動、/cpm version），以及有新版時管理員登入 3 秒後的提醒。 */
public final class UpdateNotifier implements Listener {

    public static final String PERMISSION = "pmonitor.version";

    private final PMonitor plugin;

    public UpdateNotifier(PMonitor plugin) {
        this.plugin = plugin;
    }

    /** 啟動時在後台檢查一次。 */
    public void checkOnStartup() {
        if (!this.plugin.config().updateCheckOnStartup) {
            return;
        }
        this.plugin.updates().check().thenAccept(r -> runSync(() -> {
            switch (r.status()) {
                case NEWER -> Log.warn("update-newer", "latest", r.latest(), "current", r.current(), "url", UpdateChecker.PAGE);
                case LATEST -> Log.info("update-latest", "current", r.current());
                case DEV -> Log.info("update-dev", "current", r.current());
                case FAILED -> Log.info("update-failed", "reason", reason(r));
            }
        }));
    }

    /** /cpm version */
    public void showTo(CommandSender sender) {
        this.plugin.messages().send(sender, "version.current", "version", this.plugin.getPluginMeta().getVersion());
        this.plugin.messages().send(sender, "version.checking");
        this.plugin.updates().check().thenAccept(r -> runSync(() -> {
            if (sender instanceof Player p && !p.isOnline()) {
                return;
            }
            switch (r.status()) {
                case NEWER -> this.plugin.messages().send(sender, "version.newer",
                    "latest", r.latest(), "current", r.current(), "url", UpdateChecker.PAGE);
                case LATEST -> this.plugin.messages().send(sender, "version.latest");
                case DEV -> this.plugin.messages().send(sender, "version.dev");
                case FAILED -> this.plugin.messages().send(sender, "version.failed", "reason", reason(r));
            }
        }));
    }

    /** 回到主執行緒；插件已關閉時不做事。 */
    private void runSync(Runnable task) {
        if (this.plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(this.plugin, task);
        }
    }

    private String reason(UpdateChecker.Result r) {
        return this.plugin.messages().plain(r.reasonKey(), "code", r.reasonArg());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!this.plugin.config().updateNotifyOnJoin || !player.hasPermission(PERMISSION)) {
            return;
        }
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.plugin.updates().check().thenAccept(r -> {
            if (r.status() != UpdateChecker.Status.NEWER) {
                return;
            }
            runSync(() -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    this.plugin.messages().send(p, "version.join-notify",
                        "latest", r.latest(), "current", r.current(), "url", UpdateChecker.PAGE);
                }
            });
        }), 60L);
    }
}
