package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 用聊天室輸入文字：逾時或輸入「取消」時回到原本的介面。 */
public final class ChatInput implements Listener {

    private record Request(Consumer<String> onInput, Runnable onCancel, BukkitTask timeout) {
    }

    private final PMonitor plugin;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();

    public ChatInput(PMonitor plugin) {
        this.plugin = plugin;
    }

    public boolean isWaiting(Player player) {
        return this.requests.containsKey(player.getUniqueId());
    }

    /** 開始等待輸入；onInput 與 onCancel 都在主執行緒呼叫。 */
    public void request(Player player, String promptKey, Consumer<String> onInput, Runnable onCancel) {
        cancel(player);
        int seconds = this.plugin.config().inputTimeout;
        java.util.UUID id = player.getUniqueId();
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            Request r = this.requests.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (r != null && p != null) {
                this.plugin.messages().send(p, "input-timeout");
                r.onCancel.run();
            }
        }, seconds * 20L);
        this.requests.put(player.getUniqueId(), new Request(onInput, onCancel, timeout));
        player.closeInventory();
        this.plugin.messages().send(player, promptKey, "cancel", this.plugin.config().cancelWord, "seconds", seconds);
    }

    public void cancel(Player player) {
        Request r = this.requests.remove(player.getUniqueId());
        if (r != null) {
            r.timeout.cancel();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!this.requests.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = clean(PlainTextComponentSerializer.plainText().serialize(event.message()));
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            Request r = this.requests.remove(player.getUniqueId());
            if (r == null || !player.isOnline()) {
                return;
            }
            r.timeout.cancel();
            if (text.equalsIgnoreCase(this.plugin.config().cancelWord)) {
                this.plugin.messages().send(player, "input-cancelled");
                r.onCancel.run();
            } else {
                r.onInput.accept(text);
            }
        });
    }

    /** 輸入文字只保留一般字元：去掉顏色碼符號與控制字元，最長 {@value #MAX_INPUT} 字。 */
    static String clean(String raw) {
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_INPUT));
        for (int i = 0; i < raw.length() && sb.length() < MAX_INPUT; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '&' && c != '\u00a7') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static final int MAX_INPUT = 32;
}
