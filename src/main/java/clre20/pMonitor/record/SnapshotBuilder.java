package clre20.pMonitor.record;

import clre20.pMonitor.net.ClientState;
import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.nms.PacketFields;
import clre20.pMonitor.nms.Ref;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 建立 Flashback 快照：把「客戶端此刻看到的一切」轉成一連串動作。
 * <p>
 * 必須在主執行緒呼叫。快取類資料（玩家列表、計分板…）在持有 ClientState 鎖時讀取，
 * 並在同一個鎖內掛上錄製器，確保快照與之後擷取的封包之間沒有遺漏或重複。
 * 區塊與實體則依伺服器現況重新產生，內容最準確。
 */
final class SnapshotBuilder {

    enum Type { CONFIG, GAME, CHUNK, RAW }

    /** 快照中的一個項目；RAW 是已經編碼好的動作內容。 */
    record Item(Type type, Object packet, int action, byte[] raw) {
        static Item config(Object p) {
            return new Item(Type.CONFIG, p, 0, null);
        }

        static Item game(Object p) {
            return new Item(Type.GAME, p, 0, null);
        }

        static Item chunk(Object p) {
            return new Item(Type.CHUNK, p, 0, null);
        }

        static Item raw(int action, byte[] data) {
            return new Item(Type.RAW, null, action, data);
        }
    }

    /**
     * 快照結果，連同初始的實體位置（給錄製器之後比對用）。
     * replayAfter：Flashback 載入回放時，第一個快照裡的計分板、王者血條、Tab 標題等
     * 會在觀看者加入前就被轉送而遺失，所以快照後再當一般封包送一次。
     */
    /** positions 的值與 Recorder.lastPositions 相同：[x, y, z, yRot, xRot, headRot, onGround, 存在標記]。 */
    record Result(List<Item> items, Map<Integer, double[]> positions, List<Object> replayAfter,
                  int chunkIndex, long[] chunkOrder, Object level) {
    }

    private static final boolean DEBUG = Boolean.getBoolean("pmonitor.debug.snapshot");

    private SnapshotBuilder() {
    }

    /** 依伺服器現況產生一個區塊封包；區塊已卸載時回傳 null。必須在主執行緒呼叫。 */
    static Item chunkItem(Nms nms, Object level, long key) {
        Object chunk = nms.chunkPacket(level, PacketFields.keyX(key), PacketFields.keyZ(key));
        return chunk == null ? null : Item.chunk(chunk);
    }

    /**
     * 讀取快取並在同一個鎖內執行 {@code underLock}（通常是掛上錄製器或排空佇列）。
     */
    static Result build(Nms nms, Player player, ClientState state, Runnable underLock) {
        return build(nms, player, state, underLock, false);
    }

    /**
     * @param deferChunks true 時不在這裡產生區塊封包，只回傳要產生的區塊（由近到遠）與插入位置，
     *                    由呼叫端分散到之後幾個 tick 產生（見 {@link #chunkItem}）
     */
    static Result build(Nms nms, Player player, ClientState state, Runnable underLock, boolean deferChunks) {
        Object sp = nms.handle(player);
        Object level = nms.level(sp);
        List<Item> items = new ArrayList<>();
        Map<Integer, double[]> positions = new HashMap<>();

        long[] chunkKeys;
        List<ClientState.EntityState> entities;
        int selfId;
        Object login;
        List<Object> interfacePackets;
        List<Object> environment;
        List<Object> packs;
        List<Object> selfStatus;
        List<Object> maps;
        boolean late;

        synchronized (state) {
            late = state.isLateJoin();
            if (late && state.chunkKeys().length == 0) {
                seedFromServer(nms, player, sp, level, state);
            }
            chunkKeys = state.chunkKeys();
            entities = state.entityStates();
            selfId = state.selfId() >= 0 ? state.selfId() : nms.entityId(sp);
            login = state.loginPacket();
            interfacePackets = state.interfacePackets(nms);
            environment = state.environmentPackets();
            packs = state.resourcePackPackets();
            selfStatus = state.selfStatusPackets();
            maps = state.mapPackets();
            if (DEBUG) {
                java.util.logging.Logger.getLogger("PMonitor").info("[debug] 快照 " + player.getName() + "：late=" + late
                    + " chunks=" + chunkKeys.length + " entities=" + entities.size() + " login=" + (login != null)
                    + " dim=" + state.dimension() + " playing=" + state.isPlaying() + " state=" + System.identityHashCode(state));
            }
            // 快照內容的擷取點：從這裡開始的封包都交給錄製器
            underLock.run();
        }

        // ---- 設定階段：功能旗標、完整註冊表、標籤、資源包
        for (Object p : nms.configurationPackets()) {
            items.add(Item.config(p));
        }
        items.add(Item.config(nms.resourcePackPopAll()));
        for (Object p : packs) {
            items.add(Item.config(p));
        }

        // ---- 進入遊戲
        Object spawnInfo = nms.spawnInfo(sp);
        Object loginPacket;
        if (login != null) {
            Map<String, Object> o = new HashMap<>();
            o.put("playerId", selfId);
            o.put("commonPlayerSpawnInfo", spawnInfo);
            loginPacket = Ref.copyRecord(login, o);
        } else {
            loginPacket = nms.fallbackLogin(sp, spawnInfo);
        }
        items.add(Item.game(loginPacket));
        items.add(Item.raw(FlashbackFormat.CREATE_LOCAL_PLAYER, createLocalPlayer(nms, sp)));

        // 玩家本身的資料（實體資料、屬性、裝備；不含新增實體封包）
        List<Object> selfPairing = nms.pairingPackets(level, sp, selfId);
        if (selfPairing != null) {
            for (Object p : selfPairing) {
                addGame(nms, items, p, true);
            }
        }

        // ---- 介面與環境
        if (late && interfacePackets.isEmpty()) {
            interfacePackets = List.of(nms.playerInfoAllOnline());
        }
        List<Object> replayAfter = new ArrayList<>();
        for (Object p : interfacePackets) {
            items.add(Item.game(p));
            if (p.getClass() != nms.packets.playerInfoUpdate) {
                replayAfter.add(p);
            }
        }
        for (Object p : environment) {
            items.add(Item.game(p));
        }

        // ---- 區塊（依伺服器現況重新產生，由近到遠）
        int pcx = (int) Math.floor(nms.x(sp)) >> 4;
        int pcz = (int) Math.floor(nms.z(sp)) >> 4;
        List<long[]> ordered = new ArrayList<>(chunkKeys.length);
        for (long key : chunkKeys) {
            int dx = PacketFields.keyX(key) - pcx;
            int dz = PacketFields.keyZ(key) - pcz;
            ordered.add(new long[]{key, (long) dx * dx + (long) dz * dz});
        }
        ordered.sort(Comparator.comparingLong(a -> a[1]));
        int chunkIndex = items.size();
        long[] chunkOrder = new long[ordered.size()];
        for (int i = 0; i < chunkOrder.length; i++) {
            chunkOrder[i] = ordered.get(i)[0];
        }
        if (!deferChunks) {
            for (long key : chunkOrder) {
                Item chunk = chunkItem(nms, level, key);
                if (chunk != null) {
                    items.add(chunk);
                }
            }
            chunkOrder = new long[0];
        }

        // ---- 玩家狀態：血量、經驗、效果、快捷欄
        for (Object p : selfStatus) {
            items.add(Item.game(p));
        }
        items.add(Item.game(nms.heldSlotPacket(player.getInventory().getHeldItemSlot())));
        for (int i = 0; i < 9; i++) {
            items.add(Item.game(nms.playerInventoryPacket(i, nms.copyItem(nms.hotbarItem(sp, i)))));
        }

        // ---- 實體
        positions.put(selfId, new double[]{nms.x(sp), nms.y(sp), nms.z(sp), nms.yRot(sp), nms.xRot(sp), nms.headRot(sp), nms.onGround(sp) ? 1 : 0, 0});
        List<double[]> fakePositions = new ArrayList<>();
        Set<Integer> written = new HashSet<>();
        for (ClientState.EntityState e : entities) {
            if (e.id == selfId || !written.add(e.id)) {
                continue;
            }
            Object real = nms.entityById(level, e.id);
            if (real != null) {
                List<Object> pairing = nms.pairingPackets(level, sp, e.id);
                if (pairing != null) {
                    for (Object p : pairing) {
                        addGame(nms, items, p, false);
                    }
                    positions.put(e.id, new double[]{nms.x(real), nms.y(real), nms.z(real), nms.yRot(real), nms.xRot(real), nms.headRot(real), nms.onGround(real) ? 1 : 0, 0});
                    continue;
                }
            }
            if (e.addPacket == null) {
                continue;
            }
            // 只存在於封包中的實體（全息圖、NPC 等）：用快取重建
            items.add(Item.game(e.addPacket));
            if (!e.data.isEmpty()) {
                items.add(Item.game(nms.fields.buildEntityData(e.id, new ArrayList<>(e.dataValues()))));
            }
            if (!e.equipment.isEmpty()) {
                items.add(Item.game(nms.equipmentPacket(e.id, e.equipmentList())));
            }
            if (e.passengers != null) {
                items.add(Item.game(e.passengers));
            }
            double[] pos = new double[]{e.pos[0], e.pos[1], e.pos[2], e.pos[3], e.pos[4], e.pos[5], e.onGround ? 1 : 0, 0};
            positions.put(e.id, pos);
            fakePositions.add(new double[]{e.id, pos[0], pos[1], pos[2], pos[3], pos[4], pos[5], pos[6]});
        }
        if (!fakePositions.isEmpty()) {
            items.add(Item.raw(FlashbackFormat.MOVE_ENTITIES, moveEntities(nms.dimensionId(level), fakePositions)));
        }

        // ---- 地圖
        for (Object p : maps) {
            items.add(Item.game(p));
        }
        return new Result(items, positions, replayAfter, chunkIndex, chunkOrder, level);
    }

    private static void addGame(Nms nms, List<Item> items, Object packet, boolean skipAddEntity) {
        if (packet.getClass() == nms.packets.bundle) {
            for (Object sub : nms.fields.subPackets(packet)) {
                addGame(nms, items, sub, skipAddEntity);
            }
            return;
        }
        if (skipAddEntity && packet.getClass() == nms.packets.addEntity) {
            return;
        }
        items.add(Item.game(packet));
    }

    /** 插件在玩家進服後才載入：用伺服器現況推估客戶端已有的區塊與實體。 */
    private static void seedFromServer(Nms nms, Player player, Object sp, Object level, ClientState state) {
        int radius = player.getClientViewDistance();
        int viewDistance = player.getWorld().getViewDistance();
        radius = Math.max(2, Math.min(radius, viewDistance));
        int cx = (int) Math.floor(nms.x(sp)) >> 4;
        int cz = (int) Math.floor(nms.z(sp)) >> 4;
        List<Long> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (nms.isChunkLoaded(level, cx + dx, cz + dz)) {
                    chunks.add(PacketFields.key(cx + dx, cz + dz));
                }
            }
        }
        List<int[]> ids = new ArrayList<>();
        for (int id : nms.trackedIdsSeenBy(level, sp)) {
            ids.add(new int[]{id});
        }
        state.seed(chunks, ids, nms, level);
    }

    /** ActionCreateLocalPlayer 的內容。 */
    static byte[] createLocalPlayer(Nms nms, Object sp) {
        ByteBuf buf = Unpooled.buffer(512);
        try {
            FlashbackFormat.writeUuid(buf, (java.util.UUID) Ref.call(sp, "getUUID"));
            buf.writeDouble(nms.x(sp));
            buf.writeDouble(nms.y(sp));
            buf.writeDouble(nms.z(sp));
            buf.writeFloat(nms.xRot(sp));
            buf.writeFloat(nms.yRot(sp));
            buf.writeFloat(nms.headRot(sp));
            double[] v = nms.velocity(sp);
            buf.writeDouble(v[0]);
            buf.writeDouble(v[1]);
            buf.writeDouble(v[2]);
            nms.encodeGameProfile(buf, nms.gameProfile(sp));
            FlashbackFormat.writeVarInt(buf, nms.gameModeId(sp));
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** ActionMoveEntities 的內容；每筆為 {id, x, y, z, yRot, xRot, headRot, onGround}。 */
    static byte[] moveEntities(String dimension, List<double[]> entries) {
        ByteBuf buf = Unpooled.buffer(64 + entries.size() * 48);
        try {
            FlashbackFormat.writeVarInt(buf, 1);
            FlashbackFormat.writeString(buf, dimension);
            FlashbackFormat.writeVarInt(buf, entries.size());
            for (double[] e : entries) {
                FlashbackFormat.writeVarInt(buf, (int) e[0]);
                buf.writeDouble(e[1]);
                buf.writeDouble(e[2]);
                buf.writeDouble(e[3]);
                buf.writeFloat(wrap((float) e[4]));
                buf.writeFloat(wrap((float) e[5]));
                buf.writeFloat(wrap((float) e[6]));
                buf.writeBoolean(e[7] != 0);
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** 角度換算到 -180 ~ 180。 */
    static float wrap(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }
}
