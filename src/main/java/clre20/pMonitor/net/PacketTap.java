package clre20.pMonitor.net;

import clre20.pMonitor.util.Log;
import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.nms.Ref;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.logging.Logger;

/**
 * 插在玩家連線管線上、位於封包處理器之前的出站處理器：
 * 看到的是尚未編碼的封包物件，交給 {@link ClientState} 更新快取並（若在錄製）記錄。
 * 這裡的任何錯誤都不能影響玩家連線，所以全部攔下來。
 */
public final class PacketTap extends ChannelOutboundHandlerAdapter {

    public static final String NAME = "pmonitor_tap";

    private final ClientState state;
    private final Logger logger;
    private final Class<?> packetClass;
    private boolean warned;

    public PacketTap(ClientState state, Logger logger) {
        this.state = state;
        this.logger = logger;
        this.packetClass = Nms.get().packets.packet;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (this.packetClass.isInstance(msg)) {
            try {
                Object protocol = Nms.get().protocolOf(ctx.pipeline().get("encoder"));
                if (protocol != null) {
                    this.state.onOutbound(msg, protocol);
                }
            } catch (Throwable t) {
                if (!this.warned) {
                    this.warned = true;
                    Log.warn("packet-error", "type", msg.getClass().getSimpleName(), "error", Ref.rootMessage(t));
                }
            }
        }
        super.write(ctx, msg, promise);
    }

    public ClientState state() {
        return this.state;
    }
}
