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
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            Request r = this.requests.remove(player.getUniqueId());
            if (r != null && player.isOnline()) {
                this.plugin.messages().send(player, "input-timeout");
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
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
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
}
