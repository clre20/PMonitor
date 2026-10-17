package clre20.pMonitor.net;

import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.nms.Ref;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 管理每條連線的封包攔截器與玩家狀態快取。
 * <p>
 * 透過 Paper 的 ChannelInitializeListenerHolder，在新連線建立的當下就掛上攔截器，
 * 因此能完整看到登入、設定階段與進入遊戲時的所有封包。
 */
public final class TapManager {

    private static final Key KEY = Key.key("pmonitor", "tap");

    private final Logger logger;
    private final Map<UUID, ClientState> states = new ConcurrentHashMap<>();
    private boolean listenerRegistered;

    public TapManager(Logger logger) {
        this.logger = logger;
    }

    /** 註冊新連線監聽器。 */
    public void start() {
        if (Boolean.getBoolean("pmonitor.debug.lateJoin")) {
            // 測試用：模擬插件在玩家進服後才載入
            this.logger.warning("pmonitor.debug.lateJoin 已開啟：不在連線建立時攔截");
            return;
        }
        try {
            Class<?> holder = Ref.cls("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Class<?> listenerType = Ref.cls("io.papermc.paper.network.ChannelInitializeListener");
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (proxy, method, args) -> {
                if (method.getName().equals("afterInitChannel") && args != null && args.length == 1) {
                    install((Channel) args[0]);
                    return null;
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PMonitorTapListener";
                    default -> null;
                };
            });
            Ref.method(holder, "addListener", Key.class, listenerType).invoke(null, KEY, listener);
            this.listenerRegistered = true;
        } catch (Throwable t) {
            this.logger.warning("無法註冊連線監聽器，只能在玩家進服後才開始攔截：" + Ref.rootMessage(t));
        }
    }

    /** 在連線管線上安裝攔截器（在 Netty 執行緒或建立連線時呼叫）。 */
    private ClientState install(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(PacketTap.NAME) instanceof PacketTap tap) {
            return tap.state();
        }
        ClientState state = new ClientState(this.logger);
        PacketTap tap = new PacketTap(state, this.logger);
        if (pipeline.get("packet_handler") != null) {
            pipeline.addBefore("packet_handler", PacketTap.NAME, tap);
        } else {
            pipeline.addLast(PacketTap.NAME, tap);
        }
        return state;
    }

    /** 玩家進服：把連線上的狀態快取跟玩家 UUID 綁定；若還沒掛上攔截器（例如插件中途載入）則立即掛上。 */
    public ClientState bind(Player player) {
        try {
            Channel channel = Nms.get().channel(player);
            if (channel == null) {
                return null;
            }
            ClientState state;
            Object existing = channel.pipeline().get(PacketTap.NAME);
            if (existing != null && !(existing instanceof PacketTap)) {
                // 舊版插件（重新載入前）留下的攔截器
                channel.eventLoop().submit(() -> {
                    if (channel.pipeline().get(PacketTap.NAME) != null) {
                        channel.pipeline().remove(PacketTap.NAME);
                    }
                }).syncUninterruptibly();
            }
            if (channel.pipeline().get(PacketTap.NAME) instanceof PacketTap tap) {
                state = tap.state();
            } else {
                // 中途掛上：快取裡沒有登入時的資料，之後建立快照時會改用伺服器現況補齊
                ClientState created = new ClientState(this.logger);
                created.markLateJoin();
                PacketTap tap = new PacketTap(created, this.logger);
                channel.eventLoop().submit(() -> {
                    if (channel.pipeline().get(PacketTap.NAME) == null) {
                        if (channel.pipeline().get("packet_handler") != null) {
                            channel.pipeline().addBefore("packet_handler", PacketTap.NAME, tap);
                        } else {
                            channel.pipeline().addLast(PacketTap.NAME, tap);
                        }
                    }
                }).syncUninterruptibly();
                state = channel.pipeline().get(PacketTap.NAME) instanceof PacketTap t ? t.state() : created;
            }
            state.bind(player.getUniqueId());
            this.states.put(player.getUniqueId(), state);
            return state;
        } catch (Throwable t) {
            this.logger.warning("無法攔截 " + player.getName() + " 的封包：" + Ref.rootMessage(t));
            return null;
        }
    }

    public ClientState state(UUID uuid) {
        return this.states.get(uuid);
    }

    public void forget(UUID uuid) {
        this.states.remove(uuid);
    }

    public boolean isListenerRegistered() {
        return this.listenerRegistered;
    }

    /** 插件停用：取消監聽並移除所有攔截器。 */
    public void stop(Iterable<? extends Player> players) {
        if (this.listenerRegistered) {
            try {
                Class<?> holder = Ref.cls("io.papermc.paper.network.ChannelInitializeListenerHolder");
                Ref.method(holder, "removeListener", Key.class).invoke(null, KEY);
            } catch (Throwable ignored) {
            }
        }
        for (Player player : players) {
            try {
                Channel channel = Nms.get().channel(player);
                if (channel != null) {
                    channel.eventLoop().execute(() -> {
                        try {
                            if (channel.pipeline().get(PacketTap.NAME) != null) {
                                channel.pipeline().remove(PacketTap.NAME);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
        this.states.clear();
    }
}
