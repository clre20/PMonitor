package clre20.pMonitor.net;

import clre20.pMonitor.util.Log;
import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.nms.PacketClasses;
import clre20.pMonitor.nms.PacketFields;
import clre20.pMonitor.nms.Ref;
import clre20.pMonitor.record.Recorder;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 某位玩家「客戶端目前看到的狀態」的快取，由送往該玩家的封包推算。
 * <p>
 * 用途：玩家已經在線上時隨時開始錄製，或每 5 分鐘建立一次 Flashback 快照時，
 * 需要知道客戶端已經有哪些區塊、實體、計分板、王者血條、玩家列表等。
 * <p>
 * 所有方法都在 {@code synchronized(this)} 下執行：Netty 執行緒更新、主執行緒讀取。
 */
public final class ClientState {

    private volatile UUID uuid;
    private final Logger logger;
    /** 插件在玩家進服之後才掛上攔截器：快取不完整，快照時要用伺服器現況補齊。 */
    private boolean lateJoin;

    // 連線狀態
    private Object playCodec;
    private Object loginPacket;
    private Object spawnInfo;
    private String dimension;
    private int selfId = -1;
    private boolean configuring;

    // 世界
    private final LongLinkedOpenHashSet chunks = new LongLinkedOpenHashSet();
    private final Int2ObjectLinkedOpenHashMap<EntityState> entities = new Int2ObjectLinkedOpenHashMap<>();

    // 介面
    private final LinkedHashMap<UUID, LinkedHashMap<Class<?>, Object>> bossBars = new LinkedHashMap<>();
    private Object tabList;
    private final LinkedHashMap<UUID, Object> playerInfo = new LinkedHashMap<>();

    // 計分板
    private final LinkedHashMap<String, Object[]> objectives = new LinkedHashMap<>();
    private final LinkedHashMap<Object, Object> displaySlots = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashMap<String, Object>> scores = new LinkedHashMap<>();
    private final LinkedHashMap<String, TeamState> teams = new LinkedHashMap<>();

    // 環境
    private Object time;
    private Object difficulty;
    private Object spawnPosition;
    private Object serverData;
    private Object initBorder;
    private final List<Object> borderOps = new ArrayList<>();
    private final LinkedHashMap<String, Object> gameEvents = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Object> resourcePacks = new LinkedHashMap<>();

    // 玩家本身
    private Object health;
    private Object experience;
    private final LinkedHashMap<Object, Object> selfEffects = new LinkedHashMap<>();

    // 地圖
    private final LinkedHashMap<Object, MapState> maps = new LinkedHashMap<>();

    /** 正在錄製這位玩家的錄製器（沒有則為 null）。 */
    private volatile Recorder recorder;
    private boolean warned;

    public ClientState(Logger logger) {
        this.logger = logger;
    }

    public UUID uuid() {
        return this.uuid;
    }

    public void bind(UUID uuid) {
        this.uuid = uuid;
    }

    public synchronized void markLateJoin() {
        this.lateJoin = true;
    }

    public boolean isLateJoin() {
        return this.lateJoin;
    }

    // ================================================================ 封包進入點（Netty 執行緒）

    public synchronized void onOutbound(Object packet, Object protocolInfo) {
        Nms nms = Nms.get();
        String protocol = nms.protocolName(protocolInfo);
        if (!protocol.equals("PLAY")) {
            if (protocol.equals("CONFIGURATION")) {
                this.configuring = true;
                // 伺服器資源包（server.properties 的 resource-pack）通常在設定階段送出
                Class<?> c = packet.getClass();
                try {
                    if (c == nms.packets.resourcePackPush) {
                        this.resourcePacks.put(nms.fields.packPushId(packet), packet);
                    } else if (c == nms.packets.resourcePackPop) {
                        Optional<?> id = nms.fields.packPopId(packet);
                        if (id.isEmpty()) {
                            this.resourcePacks.clear();
                        } else {
                            this.resourcePacks.remove((UUID) id.get());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (this.playCodec == null) {
            this.playCodec = nms.codecOf(protocolInfo);
        }
        handle(nms, packet);
    }

    private void handle(Nms nms, Object packet) {
        PacketClasses p = nms.packets;
        Class<?> c = packet.getClass();
        if (c == p.bundle) {
            for (Object sub : nms.fields.subPackets(packet)) {
                handle(nms, sub);
            }
            return;
        }
        try {
            update(nms, p, c, packet);
        } catch (Throwable t) {
            if (!this.warned) {
                this.warned = true;
                Log.warn("state-error", "type", c.getSimpleName(), "error", Ref.rootMessage(t));
            }
        }
        Recorder r = this.recorder;
        if (r != null) {
            r.capture(packet, this.playCodec);
        }
    }

    private void update(Nms nms, PacketClasses p, Class<?> c, Object pk) {
        PacketFields f = nms.fields;
        if (c == p.login) {
            if (Boolean.getBoolean("pmonitor.debug.snapshot")) {
                this.logger.info("[debug] login 封包 state=" + System.identityHashCode(this) + " uuid=" + this.uuid);
            }
            this.loginPacket = pk;
            this.selfId = f.loginPlayerId(pk);
            this.configuring = false;
            resetAll();
            this.spawnInfo = Ref.component(pk, "commonPlayerSpawnInfo");
            this.dimension = nms.resourceKeyId(f.spawnInfoDimension(this.spawnInfo));
        } else if (c == p.respawn) {
            this.spawnInfo = f.respawnSpawnInfo(pk);
            String dim = nms.resourceKeyId(f.spawnInfoDimension(this.spawnInfo));
            if (Boolean.getBoolean("pmonitor.debug.snapshot")) {
                this.logger.info("[debug] respawn 封包 " + this.dimension + " -> " + dim + " state=" + System.identityHashCode(this) + " chunks=" + this.chunks.size());
            }
            if (!dim.equals(this.dimension)) {
                this.chunks.clear();
                this.entities.clear();
                this.maps.clear();
            }
            this.dimension = dim;
        } else if (c == p.startConfiguration) {
            this.configuring = true;
        } else if (c == p.chunkWithLight) {
            this.chunks.add(f.chunkKey(pk));
        } else if (c == p.forgetChunk) {
            this.chunks.remove(f.forgetKey(pk));
        } else if (c == p.addEntity) {
            EntityState e = new EntityState(f.addId(pk), pk, f.addPosition(pk));
            this.entities.put(e.id, e);
        } else if (c == p.removeEntities) {
            for (int id : f.removeIds(pk)) {
                this.entities.remove(id);
            }
        } else if (c == p.setEntityData) {
            EntityState e = this.entities.get(f.dataEntity(pk));
            if (e != null) {
                for (Object v : f.dataItems(pk)) {
                    e.data.put(f.dataValueId(v), v);
                }
            }
        } else if (c == p.setEquipment) {
            EntityState e = this.entities.get(f.equipEntity(pk));
            if (e != null) {
                for (Object[] si : f.equipSlots(pk)) {
                    e.equipment.put(si[0], si[1]);
                }
            }
        } else if (c == p.setPassengers) {
            EntityState e = this.entities.get(f.passengersVehicle(pk));
            if (e != null) {
                e.passengers = pk;
            }
        } else if (c == p.teleportEntity) {
            EntityState e = this.entities.get(f.teleportId(pk));
            if (e != null) {
                f.applyTeleport(pk, e.pos);
                e.pos[5] = e.pos[3];
                e.onGround = f.teleportOnGround(pk);
                e.moved = true;
            }
        } else if (c == p.positionSync) {
            EntityState e = this.entities.get(f.syncId(pk));
            if (e != null) {
                double[] s = f.syncPosition(pk);
                System.arraycopy(s, 0, e.pos, 0, 5);
                e.onGround = f.syncOnGround(pk);
                e.moved = true;
            }
        } else if (p.isMoveEntity(c)) {
            EntityState e = this.entities.get(f.moveId(pk));
            if (e != null) {
                e.onGround = f.applyMove(pk, e.pos);
                e.moved = true;
            }
        } else if (c == p.rotateHead) {
            EntityState e = this.entities.get(f.headId(pk));
            if (e != null) {
                e.pos[5] = f.headRot(pk);
                e.moved = true;
            }
        } else if (c == p.bossEvent) {
            UUID id = f.bossId(pk);
            if (f.isBossRemove(pk)) {
                this.bossBars.remove(id);
            } else if (f.isBossAdd(pk)) {
                LinkedHashMap<Class<?>, Object> ops = new LinkedHashMap<>();
                ops.put(f.bossOperation(pk).getClass(), pk);
                this.bossBars.put(id, ops);
            } else {
                LinkedHashMap<Class<?>, Object> ops = this.bossBars.get(id);
                if (ops != null) {
                    ops.put(f.bossOperation(pk).getClass(), pk);
                }
            }
        } else if (c == p.tabList) {
            this.tabList = pk;
        } else if (c == p.playerInfoUpdate) {
            Set<?> actions = f.infoActions(pk);
            boolean add = false;
            for (Object a : actions) {
                if (((Enum<?>) a).name().equals("ADD_PLAYER")) {
                    add = true;
                }
            }
            for (Object entry : f.infoEntries(pk)) {
                UUID id = f.entryProfileId(entry);
                Object old = this.playerInfo.get(id);
                if (add || old == null) {
                    this.playerInfo.put(id, entry);
                } else {
                    this.playerInfo.put(id, f.mergeEntry(old, entry, actions));
                }
            }
        } else if (c == p.playerInfoRemove) {
            for (Object id : f.infoRemoveIds(pk)) {
                this.playerInfo.remove((UUID) id);
            }
        } else if (c == p.setPlayerTeam) {
            updateTeam(f, pk);
        } else if (c == p.setObjective) {
            String name = f.objectiveName(pk);
            int method = f.objectiveMethod(pk);
            if (method == 0) {
                this.objectives.put(name, new Object[]{pk, null});
            } else if (method == 1) {
                this.objectives.remove(name);
                this.scores.remove(name);
                this.displaySlots.values().removeIf(d -> name.equals(f.displayObjective(d)));
            } else {
                Object[] o = this.objectives.get(name);
                if (o != null) {
                    o[1] = pk;
                }
            }
        } else if (c == p.setDisplayObjective) {
            this.displaySlots.put(f.displaySlot(pk), pk);
        } else if (c == p.setScore) {
            this.scores.computeIfAbsent(f.scoreObjective(pk), k -> new LinkedHashMap<>()).put(f.scoreOwner(pk), pk);
        } else if (c == p.resetScore) {
            String obj = f.resetObjective(pk);
            String owner = f.resetOwner(pk);
            if (obj == null) {
                for (LinkedHashMap<String, Object> m : this.scores.values()) {
                    m.remove(owner);
                }
            } else {
                LinkedHashMap<String, Object> m = this.scores.get(obj);
                if (m != null) {
                    m.remove(owner);
                }
            }
        } else if (c == p.setTime) {
            this.time = pk;
        } else if (c == p.changeDifficulty) {
            this.difficulty = pk;
        } else if (c == p.setDefaultSpawn) {
            this.spawnPosition = pk;
        } else if (c == p.serverData) {
            this.serverData = pk;
        } else if (c == p.initializeBorder) {
            this.initBorder = pk;
            this.borderOps.clear();
        } else if (p.borderOps.contains(c)) {
            if (this.borderOps.size() > 64) {
                this.borderOps.remove(0);
            }
            this.borderOps.add(pk);
        } else if (c == p.gameEvent) {
            String name = f.gameEventName(pk);
            if (name != null) {
                if (name.equals("START_RAINING") || name.equals("STOP_RAINING")) {
                    name = "RAIN";
                }
                this.gameEvents.put(name, pk);
            }
        } else if (c == p.resourcePackPush) {
            this.resourcePacks.put(f.packPushId(pk), pk);
        } else if (c == p.resourcePackPop) {
            Optional<?> id = f.packPopId(pk);
            if (id.isEmpty()) {
                this.resourcePacks.clear();
            } else {
                this.resourcePacks.remove((UUID) id.get());
            }
        } else if (c == p.setHealth) {
            this.health = pk;
        } else if (c == p.setExperience) {
            this.experience = pk;
        } else if (c == p.updateMobEffect) {
            if (f.effectEntity(pk) == this.selfId) {
                this.selfEffects.put(f.effectHolder(pk), pk);
            }
        } else if (c == p.removeMobEffect) {
            if (f.removeEffectEntity(pk) == this.selfId) {
                this.selfEffects.remove(f.removeEffectHolder(pk));
            }
        } else if (c == p.mapItemData) {
            updateMap(pk);
        }
    }

    private void updateTeam(PacketFields f, Object pk) {
        String name = f.teamName(pk);
        int method = f.teamMethod(pk);
        switch (method) {
            case 0 -> {
                TeamState t = new TeamState();
                t.parameters = f.teamParameters(pk);
                for (Object player : f.teamPlayers(pk)) {
                    t.members.add((String) player);
                }
                this.teams.put(name, t);
            }
            case 1 -> this.teams.remove(name);
            case 2 -> {
                TeamState t = this.teams.get(name);
                if (t != null) {
                    t.parameters = f.teamParameters(pk);
                }
            }
            case 3 -> {
                TeamState t = this.teams.get(name);
                if (t != null) {
                    for (Object player : f.teamPlayers(pk)) {
                        t.members.add((String) player);
                    }
                }
            }
            case 4 -> {
                TeamState t = this.teams.get(name);
                if (t != null) {
                    for (Object player : f.teamPlayers(pk)) {
                        t.members.remove((String) player);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void updateMap(Object pk) {
        Object id = Ref.component(pk, "mapId");
        MapState m = this.maps.computeIfAbsent(id, k -> new MapState());
        m.scale = Ref.component(pk, "scale");
        m.locked = Ref.component(pk, "locked");
        Optional<?> decorations = (Optional<?>) Ref.component(pk, "decorations");
        if (decorations.isPresent()) {
            m.decorations = decorations;
        }
        Optional<?> patch = (Optional<?>) Ref.component(pk, "colorPatch");
        if (patch.isPresent()) {
            Object pt = patch.get();
            m.patchClass = pt.getClass();
            int sx = (int) Ref.component(pt, "startX");
            int sy = (int) Ref.component(pt, "startY");
            int w = (int) Ref.component(pt, "width");
            int h = (int) Ref.component(pt, "height");
            byte[] colors = (byte[]) Ref.component(pt, "mapColors");
            if (m.colors == null) {
                m.colors = new byte[128 * 128];
            }
            for (int dx = 0; dx < w; dx++) {
                for (int dy = 0; dy < h; dy++) {
                    int x = sx + dx;
                    int y = sy + dy;
                    if (x < 128 && y < 128) {
                        m.colors[x + y * 128] = colors[dx + dy * w];
                    }
                }
            }
        }
        m.packetClass = pk.getClass();
    }

    private void resetAll() {
        this.chunks.clear();
        this.entities.clear();
        this.bossBars.clear();
        this.tabList = null;
        this.playerInfo.clear();
        this.objectives.clear();
        this.displaySlots.clear();
        this.scores.clear();
        this.teams.clear();
        this.selfEffects.clear();
        this.maps.clear();
        this.borderOps.clear();
        this.initBorder = null;
        this.gameEvents.clear();
        // 資源包不清除：設定階段送的資源包在進入遊戲後仍然有效
    }

    /** 插件中途載入時，用伺服器現況填入區塊與實體（需在 synchronized 區塊內呼叫）。 */
    public void seed(List<Long> chunkKeys, List<int[]> entityIds, Nms nms, Object level) {
        for (long k : chunkKeys) {
            this.chunks.add(k);
        }
        for (int[] id : entityIds) {
            if (!this.entities.containsKey(id[0])) {
                Object real = nms.entityById(level, id[0]);
                double[] pos = real == null ? new double[6]
                    : new double[]{nms.x(real), nms.y(real), nms.z(real), nms.yRot(real), nms.xRot(real), nms.headRot(real)};
                this.entities.put(id[0], new EntityState(id[0], null, pos));
            }
        }
    }

    // ================================================================ 錄製器連結

    public Recorder recorder() {
        return this.recorder;
    }

    /** 在持有鎖的情況下掛上錄製器：之後的封包都會送進錄製器，保證不會跟快照重複或遺漏。 */
    public synchronized void attach(Recorder recorder) {
        this.recorder = recorder;
    }

    public synchronized void detach(Recorder recorder) {
        if (this.recorder == recorder) {
            this.recorder = null;
        }
    }

    // ================================================================ 快照讀取（主執行緒，需在 synchronized 區塊內呼叫）

    public boolean isPlaying() {
        return this.playCodec != null && (this.loginPacket != null || this.lateJoin) && !this.configuring;
    }

    public Object playCodec() {
        return this.playCodec;
    }

    public Object loginPacket() {
        return this.loginPacket;
    }

    public int selfId() {
        return this.selfId;
    }

    public String dimension() {
        return this.dimension;
    }

    public long[] chunkKeys() {
        return this.chunks.toLongArray();
    }

    public List<EntityState> entityStates() {
        return new ArrayList<>(this.entities.values());
    }

    public EntityState entity(int id) {
        return this.entities.get(id);
    }

    public int[] entityIds() {
        return this.entities.keySet().toIntArray();
    }

    /** 介面類封包（玩家列表、王者血條、計分板、環境）依建立快照所需的順序輸出。 */
    public List<Object> interfacePackets(Nms nms) {
        PacketFields f = nms.fields;
        List<Object> out = new ArrayList<>();
        if (!this.playerInfo.isEmpty()) {
            out.add(f.buildPlayerInfo(new ArrayList<>(this.playerInfo.values())));
        }
        if (this.tabList != null) {
            out.add(this.tabList);
        }
        for (LinkedHashMap<Class<?>, Object> ops : this.bossBars.values()) {
            out.addAll(ops.values());
        }
        for (Map.Entry<String, TeamState> e : this.teams.entrySet()) {
            out.add(f.buildTeamAdd(e.getKey(), e.getValue().parameters, e.getValue().members));
        }
        for (Object[] o : this.objectives.values()) {
            out.add(o[0]);
            if (o[1] != null) {
                out.add(o[1]);
            }
        }
        out.addAll(this.displaySlots.values());
        for (Map.Entry<String, LinkedHashMap<String, Object>> e : this.scores.entrySet()) {
            if (this.objectives.containsKey(e.getKey())) {
                out.addAll(e.getValue().values());
            }
        }
        return out;
    }

    public List<Object> environmentPackets() {
        List<Object> out = new ArrayList<>();
        if (this.serverData != null) {
            out.add(this.serverData);
        }
        if (this.difficulty != null) {
            out.add(this.difficulty);
        }
        if (this.initBorder != null) {
            out.add(this.initBorder);
            out.addAll(this.borderOps);
        }
        if (this.time != null) {
            out.add(this.time);
        }
        if (this.spawnPosition != null) {
            out.add(this.spawnPosition);
        }
        out.addAll(this.gameEvents.values());
        return out;
    }

    public List<Object> resourcePackPackets() {
        return new ArrayList<>(this.resourcePacks.values());
    }

    public List<Object> selfStatusPackets() {
        List<Object> out = new ArrayList<>();
        if (this.health != null) {
            out.add(this.health);
        }
        if (this.experience != null) {
            out.add(this.experience);
        }
        out.addAll(this.selfEffects.values());
        return out;
    }

    public List<Object> mapPackets() {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<Object, MapState> e : this.maps.entrySet()) {
            MapState m = e.getValue();
            try {
                Map<String, Object> values = new HashMap<>();
                values.put("mapId", e.getKey());
                values.put("scale", m.scale);
                values.put("locked", m.locked);
                values.put("decorations", m.decorations == null ? Optional.empty() : m.decorations);
                if (m.colors != null && m.patchClass != null) {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("startX", 0);
                    patch.put("startY", 0);
                    patch.put("width", 128);
                    patch.put("height", 128);
                    patch.put("mapColors", m.colors.clone());
                    values.put("colorPatch", Optional.of(Ref.buildRecord(m.patchClass, patch)));
                } else {
                    values.put("colorPatch", Optional.empty());
                }
                out.add(Ref.buildRecord(m.packetClass, values));
            } catch (Throwable ignored) {
                // 地圖資料只是輔助，失敗就略過
            }
        }
        return out;
    }

    // ================================================================ 內部資料

    /** 客戶端看得到的一個實體。 */
    public static final class EntityState {
        public final int id;
        public final Object addPacket;
        /** {x, y, z, yRot, xRot, headRot} */
        public final double[] pos;
        public boolean onGround;
        public boolean moved;
        public final LinkedHashMap<Integer, Object> data = new LinkedHashMap<>();
        public final LinkedHashMap<Object, Object> equipment = new LinkedHashMap<>();
        public Object passengers;

        EntityState(int id, Object addPacket, double[] pos) {
            this.id = id;
            this.addPacket = addPacket;
            this.pos = pos;
        }

        public List<Object[]> equipmentList() {
            List<Object[]> out = new ArrayList<>();
            for (Map.Entry<Object, Object> e : this.equipment.entrySet()) {
                out.add(new Object[]{e.getKey(), e.getValue()});
            }
            return out;
        }

        public Collection<Object> dataValues() {
            return new ArrayList<>(this.data.values());
        }
    }

    private static final class TeamState {
        Optional<?> parameters = Optional.empty();
        final LinkedHashSet<String> members = new LinkedHashSet<>();
    }

    private static final class MapState {
        Object scale;
        Object locked;
        Optional<?> decorations;
        byte[] colors;
        Class<?> patchClass;
        Class<?> packetClass;
    }
}
