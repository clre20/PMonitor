package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 指令與介面共用的檔案操作。 */
public final class FileActions {

    private FileActions() {
    }

    /** 相對於伺服器根目錄的路徑，例如 plugins/PMonitor/recordings/…/xxx.zip。 */
    public static String displayPath(Path path) {
        try {
            Path root = Bukkit.getWorldContainer().getAbsoluteFile().toPath().normalize();
            Path abs = path.toAbsolutePath().normalize();
            if (abs.startsWith(root)) {
                return root.relativize(abs).toString().replace('\\', '/');
            }
            return abs.toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    public static void sendDownload(PMonitor plugin, CommandSender sender, FileEntry e) {
        Messages m = plugin.messages();
        if (!plugin.config().downloadEnabled) {
            m.send(sender, "download-disabled");
            m.send(sender, "download-path", "path", displayPath(e.path()));
            return;
        }
        String url = plugin.download().createLink(e.path());
        if (url == null) {
            m.send(sender, "download-not-running");
            m.send(sender, "download-path", "path", displayPath(e.path()));
            return;
        }
        m.send(sender, "download-link", "minutes", plugin.config().downloadExpireMinutes, "file", e.name());
        if (sender instanceof Player) {
            Component link = m.component("download-click", "file", e.name())
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url)));
            sender.sendMessage(link);
        } else {
            sender.sendMessage(url);
        }
    }

    public static boolean delete(PMonitor plugin, CommandSender sender, FileEntry e) {
        Messages m = plugin.messages();
        if (e.recording()) {
            m.send(sender, "delete-recording");
            return false;
        }
        try {
            Files.deleteIfExists(e.path());
            plugin.index().remove(e.name());
            plugin.storage().requestCheck();
            m.send(sender, "deleted", "file", e.name());
            plugin.getLogger().info(sender.getName() + " 刪除了錄製檔 " + e.name());
            return true;
        } catch (IOException ex) {
            m.send(sender, "delete-failed", "file", e.name(), "error", ex.getMessage());
            return false;
        }
    }
}
