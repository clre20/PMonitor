package clre20.pMonitor.command;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.Messages;
import clre20.pMonitor.data.PlayerDataStore;
import clre20.pMonitor.record.Recorder;
import clre20.pMonitor.record.RecordingManager;
import clre20.pMonitor.ui.FileActions;
import clre20.pMonitor.ui.FileEntry;
import clre20.pMonitor.util.Fmt;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** /pmonitor（別名 /cpm） */
public final class PMonitorCommand implements TabExecutor {

    private record Sub(String name, String permission, String usageKey) {
    }

    private static final List<Sub> SUBS = List.of(
        new Sub("help", "pmonitor.help", "help.help"),
        new Sub("version", "pmonitor.version", "help.version"),
        new Sub("start", "pmonitor.start", "help.start"),
        new Sub("stop", "pmonitor.stop", "help.stop"),
        new Sub("status", "pmonitor.status", "help.status"),
        new Sub("list", "pmonitor.list", "help.list"),
        new Sub("download", "pmonitor.download", "help.download"),
        new Sub("delete", "pmonitor.delete", "help.delete"),
        new Sub("reload", "pmonitor.reload", "help.reload")
    );

    private record PendingDelete(String file, long expires) {
    }

    private final PMonitor plugin;
    private final Map<UUID, PendingDelete> pendingDeletes = new HashMap<>();

    public PMonitorCommand(PMonitor plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return this.plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                help(sender, label);
                return true;
            }
            if (!sender.hasPermission("pmonitor.use")) {
                msg().send(sender, "no-permission");
                return true;
            }
            this.plugin.menus().openMain(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Sub def = null;
        for (Sub s : SUBS) {
            if (s.name.equals(sub)) {
                def = s;
            }
        }
        if (def == null) {
            msg().send(sender, "unknown-command", "label", label);
            return true;
        }
        if (!sender.hasPermission(def.permission)) {
            msg().send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "help" -> help(sender, label);
            case "version" -> this.plugin.updateNotifier().showTo(sender);
            case "start" -> start(sender, label, args);
            case "stop" -> stop(sender, label, args);
            case "status" -> status(sender);
            case "list" -> list(sender, args);
            case "download" -> download(sender, label, args);
            case "delete" -> delete(sender, label, args);
            case "reload" -> {
                this.plugin.reload();
                msg().send(sender, "reloaded");
            }
            default -> {
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 子指令

    private void help(CommandSender sender, String label) {
        msg().sendPlain(sender, "help.header", "version", this.plugin.getPluginMeta().getVersion());
        if (sender.hasPermission("pmonitor.use")) {
            msg().sendPlain(sender, "help.open", "label", label);
        }
        for (Sub s : SUBS) {
            if (sender.hasPermission(s.permission)) {
                msg().sendPlain(sender, s.usageKey, "label", label);
            }
        }
        if (this.plugin.nmsError() != null) {
            msg().send(sender, "record-unavailable", "reason", this.plugin.nmsError());
        }
    }

    private void start(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            msg().send(sender, "usage", "usage", msg().raw("help.start", "label", label));
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        RecordingManager rm = this.plugin.recordings();
        if (online != null) {
            RecordingManager.StartResult r = rm.start(online);
            switch (r) {
                case STARTED -> msg().send(sender, "record-started", "player", online.getName());
                case WAITING -> msg().send(sender, "record-waiting", "player", online.getName());
                case ALREADY_RECORDING -> msg().send(sender, "record-already", "player", online.getName());
                case BLOCKED -> msg().send(sender, "record-blocked-storage", "used", Fmt.size(this.plugin.storage().usedBytes()),
                    "limit", Fmt.size(this.plugin.config().stopSizeBytes));
                case UNAVAILABLE -> msg().send(sender, "record-unavailable", "reason", String.valueOf(this.plugin.nmsError()));
                default -> msg().send(sender, "record-start-failed", "player", online.getName());
            }
            return;
        }
        Known known = findKnown(args[1]);
        if (known == null) {
            msg().send(sender, "unknown-player", "player", args[1]);
            return;
        }
        if (rm.arm(known.uuid, known.name) == RecordingManager.StartResult.ALREADY_ARMED) {
            msg().send(sender, "record-already-armed", "player", known.name);
        } else {
            msg().send(sender, "record-armed", "player", known.name);
        }
    }

    private void stop(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            msg().send(sender, "usage", "usage", msg().raw("help.stop", "label", label));
            return;
        }
        Known known = findKnown(args[1]);
        if (known == null) {
            msg().send(sender, "unknown-player", "player", args[1]);
            return;
        }
        RecordingManager rm = this.plugin.recordings();
        boolean wasActive = rm.recorder(known.uuid) != null;
        boolean stopped = rm.stop(known.uuid, Recorder.StopReason.MANUAL, file -> {
            if (file != null && (!(sender instanceof Player p) || p.isOnline())) {
                msg().send(sender, "record-saved", "player", known.name, "file", file.baseName());
            }
        });
        PlayerDataStore.Entry data = this.plugin.playerData().get(known.uuid);
        boolean auto = data != null && data.autoRecord;
        if (stopped) {
            msg().send(sender, wasActive ? "record-stopped" : "record-cancel-waiting", "player", known.name);
        } else if (rm.disarm(known.uuid)) {
            msg().send(sender, "record-disarmed", "player", known.name);
        } else if (auto) {
            // 沒有在錄、也沒有待命，但開著每次進服自動錄製：把自動錄製關掉
            data.autoRecord = false;
            this.plugin.playerData().save(data);
            msg().send(sender, "auto-off", "player", known.name);
            return;
        } else {
            msg().send(sender, "record-not-recording", "player", known.name);
            return;
        }
        if (auto) {
            msg().send(sender, "record-auto-still-on", "player", known.name, "label", label);
        }
    }

    private void status(CommandSender sender) {
        msg().sendPlain(sender, "status-header");
        RecordingManager rm = this.plugin.recordings();
        int lines = 0;
        for (Recorder r : rm.activeRecorders()) {
            msg().sendPlain(sender, "status-recording-line", "player", r.playerName(),
                "duration", Fmt.duration(System.currentTimeMillis() - r.startMillis()), "size", Fmt.size(r.bytesWritten()),
                "part", r.file().part());
            lines++;
        }
        for (UUID uuid : rm.pendingPlayers()) {
            Player p = Bukkit.getPlayer(uuid);
            msg().sendPlain(sender, "status-waiting-line", "player", p == null ? uuid.toString() : p.getName());
            lines++;
        }
        for (PlayerDataStore.Entry e : this.plugin.playerData().armedOrAuto()) {
            if (e.armed) {
                msg().sendPlain(sender, "status-armed-line", "player", e.name);
                lines++;
            }
            if (e.autoRecord) {
                msg().sendPlain(sender, "status-auto-line", "player", e.name);
                lines++;
            }
        }
        if (lines == 0) {
            msg().sendPlain(sender, "status-none");
        }
        var c = this.plugin.config();
        msg().sendPlain(sender, "status-storage", "used", Fmt.size(this.plugin.storage().usedBytes()),
            "warn", c.warnSizeBytes > 0 ? Fmt.size(c.warnSizeBytes) : msg().raw("storage-no-limit"),
            "stop", c.stopSizeBytes > 0 ? Fmt.size(c.stopSizeBytes) : msg().raw("storage-no-limit"));
        if (rm.isStorageBlocked()) {
            msg().sendPlain(sender, "status-blocked");
        }
    }

    private void list(CommandSender sender, String[] args) {
        UUID filter = null;
        String name = null;
        if (args.length >= 2) {
            Known known = findKnown(args[1]);
            if (known == null) {
                msg().send(sender, "unknown-player", "player", args[1]);
                return;
            }
            filter = known.uuid;
            name = known.name;
        }
        List<FileEntry> entries = FileEntry.list(this.plugin, filter);
        if (name == null) {
            msg().sendPlain(sender, "list-header", "count", entries.size());
        } else {
            msg().sendPlain(sender, "list-header-player", "player", name, "count", entries.size());
        }
        if (entries.isEmpty()) {
            msg().sendPlain(sender, "list-empty");
            return;
        }
        int shown = 0;
        for (FileEntry e : entries) {
            if (shown++ >= 50) {
                msg().sendPlain(sender, "list-more", "count", entries.size() - 50);
                break;
            }
            msg().sendPlain(sender, "list-line", e.placeholders(msg()));
        }
    }

    private void download(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            msg().send(sender, "usage", "usage", msg().raw("help.download", "label", label));
            return;
        }
        FileEntry e = FileEntry.find(this.plugin, args[1]);
        if (e == null) {
            msg().send(sender, "file-not-found", "file", args[1]);
            return;
        }
        if (e.recording()) {
            msg().send(sender, "download-recording");
            return;
        }
        FileActions.sendDownload(this.plugin, sender, e);
    }

    private void delete(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            msg().send(sender, "usage", "usage", msg().raw("help.delete", "label", label));
            return;
        }
        FileEntry e = FileEntry.find(this.plugin, args[1]);
        if (e == null) {
            msg().send(sender, "file-not-found", "file", args[1]);
            return;
        }
        if (e.recording()) {
            msg().send(sender, "delete-recording");
            return;
        }
        if (sender instanceof Player p) {
            PendingDelete pd = this.pendingDeletes.get(p.getUniqueId());
            if (pd == null || !pd.file.equals(e.name()) || pd.expires < System.currentTimeMillis()) {
                long now = System.currentTimeMillis();
                this.pendingDeletes.values().removeIf(d -> d.expires < now);
                this.pendingDeletes.put(p.getUniqueId(), new PendingDelete(e.name(), now + 30_000));
                msg().send(sender, "delete-confirm", "file", e.name(), "label", label);
                return;
            }
            this.pendingDeletes.remove(p.getUniqueId());
        }
        FileActions.delete(this.plugin, sender, e);
    }

    // ---------------------------------------------------------------- 玩家查詢

    private record Known(UUID uuid, String name) {
    }

    /** 找曾進過伺服器的玩家（支援基岩版「.」開頭的名稱）。 */
    private Known findKnown(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return new Known(p.getUniqueId(), p.getName());
        }
        for (PlayerDataStore.Entry e : this.plugin.playerData().all()) {
            if (e.name != null && e.name.equalsIgnoreCase(name)) {
                return new Known(e.uuid, e.name);
            }
        }
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op != null && (op.hasPlayedBefore() || op.isOnline())) {
            return new Known(op.getUniqueId(), op.getName() == null ? name : op.getName());
        }
        return null;
    }

    // ---------------------------------------------------------------- Tab 補全

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Sub s : SUBS) {
                if (sender.hasPermission(s.permission)) {
                    out.add(s.name);
                }
            }
            return filter(out, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            Sub def = SUBS.stream().filter(s -> s.name.equals(sub)).findFirst().orElse(null);
            if (def == null || !sender.hasPermission(def.permission)) {
                return out;
            }
            switch (sub) {
                case "start", "stop", "list" -> {
                    Set<String> names = new LinkedHashSet<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        names.add(p.getName());
                    }
                    if (sub.equals("stop")) {
                        names.clear();
                        for (Recorder r : this.plugin.recordings().activeRecorders()) {
                            names.add(r.playerName());
                        }
                        for (PlayerDataStore.Entry e : this.plugin.playerData().armedOrAuto()) {
                            names.add(e.name);
                        }
                    } else {
                        for (PlayerDataStore.Entry e : this.plugin.playerData().all()) {
                            names.add(e.name);
                        }
                    }
                    out.addAll(names);
                }
                case "download", "delete" -> {
                    for (FileEntry e : FileEntry.list(this.plugin, null)) {
                        if (!e.recording()) {
                            out.add(e.name());
                        }
                    }
                }
                default -> {
                }
            }
            return filter(out, args[1]);
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o != null && o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
