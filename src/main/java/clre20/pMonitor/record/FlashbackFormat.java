package clre20.pMonitor.record;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Flashback 回放檔格式的常數與基本編碼。
 * <pre>
 * 回放檔（.zip）
 * ├─ metadata.json            名稱、版本、各段長度
 * ├─ c0.flashback, c1...      每段：檔頭 + 快照 + 動作
 * └─ level_chunk_caches/N     區塊封包（去重複，每檔 10000 個）
 *
 * 每段檔案：
 *   int   MAGIC
 *   varint 動作數量，接著每個動作名稱（字串）
 *   int   快照長度，接著快照內容（一連串動作）
 *   之後是一連串動作：varint 動作編號、int 長度、內容
 * </pre>
 */
public final class FlashbackFormat {

    public static final int MAGIC = 0xD780E884;
    public static final int CHUNK_CACHE_SIZE = 10000;
    /** 每段的長度（tick）；Flashback 跳轉時從段落開頭的快照開始重播，太長會讓跳轉變慢。 */
    public static final int TICKS_PER_SEGMENT = Math.max(200, Integer.getInteger("pmonitor.segmentTicks", 5 * 60 * 20));

    public static final List<String> ACTIONS = List.of(
        "flashback:action/next_tick",
        "flashback:action/game_packet",
        "flashback:action/configuration_packet",
        "flashback:action/create_local_player",
        "flashback:action/move_entities",
        "flashback:action/level_chunk_cached"
    );

    public static final int NEXT_TICK = 0;
    public static final int GAME_PACKET = 1;
    public static final int CONFIGURATION_PACKET = 2;
    public static final int CREATE_LOCAL_PLAYER = 3;
    public static final int MOVE_ENTITIES = 4;
    public static final int LEVEL_CHUNK_CACHED = 5;

    private FlashbackFormat() {
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        while (true) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IllegalStateException("VarInt 太長");
            }
        }
    }

    public static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    /** 每段檔案的檔頭（MAGIC 與動作名稱表）。 */
    public static void writeHeader(ByteBuf buf) {
        buf.writeInt(MAGIC);
        writeVarInt(buf, ACTIONS.size());
        for (String name : ACTIONS) {
            writeString(buf, name);
        }
    }
}
