package clre20.pMonitor.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 讀取封包內容（給玩家狀態快取使用）。大多直接讀私有欄位，欄位名稱在各版本較穩定。 */
public final class PacketFields {

    private final PacketClasses p;

    // 登入 / 重生
    private final Ref.Getter respawnSpawnInfo;
    private final Ref.Getter spawnInfoDimension;

    // 實體
    private final Ref.Getter addId;
    private final Ref.Getter addUuid;
    private final Ref.Getter addType;
    private final Ref.Getter addX;
    private final Ref.Getter addY;
    private final Ref.Getter addZ;
    private final Ref.Getter addXRot;
    private final Ref.Getter addYRot;
    private final Ref.Getter addHeadRot;
    private final Ref.Getter removeIds;
    private final Ref.Getter chunkX;
    private final Ref.Getter chunkZ;
    private final Ref.Getter forgetPos;
    private final Ref.Getter chunkPosX;
    private final Ref.Getter chunkPosZ;
    private final Ref.Getter dataId;
    private final Ref.Getter dataItems;
    private final Ref.Invoker dataValueId;
    private final Ref.Getter equipEntity;
    private final Ref.Getter equipSlots;
    private final Ref.Invoker pairFirst;
    private final Ref.Invoker pairSecond;
    private final Ref.Getter passengersVehicle;
    private final Ref.Getter teleportId;
    private final Ref.Getter teleportChange;
    private final Ref.Getter teleportRelatives;
    private final Ref.Getter teleportOnGround;
    private final Ref.Getter pmrPosition;
    private final Ref.Getter pmrYRot;
    private final Ref.Getter pmrXRot;
    private final Ref.Getter syncId;
    private final Ref.Getter syncOnGround;
    private final Ref.Getter syncValues;     // 26.2 以前
    private final Ref.Getter syncPosition;   // 26.3 起（PositionPath）
    private final Ref.Getter syncYRot;
    private final Ref.Getter syncXRot;
    private final Ref.Invoker pathEnd;
    private final Ref.Getter moveId;
    private final Ref.Getter moveXa;         // 26.1 以前
    private final Ref.Getter moveYa;
    private final Ref.Getter moveZa;
    private final Ref.Getter moveDelta;      // 26.2 起
    private final Ref.Invoker deltaDecode;
    private final Ref.Getter moveYRot;
    private final Ref.Getter moveXRot;
    private final Ref.Getter moveHasPos;
    private final Ref.Getter moveHasRot;
    private final Ref.Getter moveOnGround;
    private final Ref.Getter headId;
    private final Ref.Getter headRot;
    private final Class<?> vecDeltaCodec;
    private final Ref.Getter codecBase;
    private final Constructor<?> vec3Ctor;
    private final Ref.Getter vecX;
    private final Ref.Getter vecY;
    private final Ref.Getter vecZ;

    // 王者血條
    private final Ref.Getter bossId;
    private final Ref.Getter bossOperation;
    private final Object bossRemoveOperation;

    // 玩家列表
    private final Ref.Getter infoActions;
    private final Ref.Getter infoEntries;
    private final Ref.Invoker entryProfileId;
    private final Ref.Getter infoRemoveIds;
    private final Constructor<?> infoUpdateCtor;
    private final Class<? extends Enum<?>> infoActionClass;

    // 計分板
    private final Ref.Getter teamName;
    private final Ref.Getter teamMethod;
    private final Ref.Getter teamPlayers;
    private final Ref.Getter teamParameters;
    private final Constructor<?> teamCtor;
    private final Ref.Getter objectiveName;
    private final Ref.Getter objectiveMethod;
    private final Ref.Getter displaySlot;
    private final Ref.Getter displayObjective;
    private final Ref.Getter scoreOwner;
    private final Ref.Getter scoreObjective;
    private final Ref.Getter resetOwner;
    private final Ref.Getter resetObjective;

    // 其他
    private final Ref.Getter gameEventType;
    private final Map<String, Object> gameEventTypes = new HashMap<>();
    private final Ref.Getter packPushId;
    private final Ref.Getter packPopId;
    private final Ref.Getter effectEntity;
    private final Ref.Getter effectHolder;
    private final Ref.Getter removeEffectEntity;
    private final Ref.Getter removeEffectHolder;
    private final Ref.Getter loginPlayerId;
    private final Ref.Invoker bundleSubPackets;

    @SuppressWarnings("unchecked")
    PacketFields(PacketClasses p) {
        this.p = p;
        this.respawnSpawnInfo = Ref.getter(p.respawn, "commonPlayerSpawnInfo");
        Class<?> spawnInfo = Ref.cls("net.minecraft.network.protocol.game.CommonPlayerSpawnInfo");
        this.spawnInfoDimension = Ref.getter(spawnInfo, "dimension");
        this.loginPlayerId = Ref.getter(p.login, "playerId");
        this.bundleSubPackets = Ref.invoker(Ref.cls("net.minecraft.network.protocol.BundlePacket"), "subPackets");

        this.addId = Ref.getter(p.addEntity, "id");
        this.addUuid = Ref.getter(p.addEntity, "uuid");
        this.addType = Ref.getter(p.addEntity, "type");
        this.addX = Ref.getter(p.addEntity, "x");
        this.addY = Ref.getter(p.addEntity, "y");
        this.addZ = Ref.getter(p.addEntity, "z");
        this.addXRot = Ref.getter(p.addEntity, "xRot");
        this.addYRot = Ref.getter(p.addEntity, "yRot");
        this.addHeadRot = Ref.getter(p.addEntity, "yHeadRot");
        this.removeIds = Ref.getter(p.removeEntities, "entityIds");
        this.chunkX = Ref.getter(p.chunkWithLight, "x");
        this.chunkZ = Ref.getter(p.chunkWithLight, "z");
        this.forgetPos = Ref.getter(p.forgetChunk, "pos");
        Class<?> chunkPos = Ref.cls("net.minecraft.world.level.ChunkPos");
        this.chunkPosX = Ref.getter(chunkPos, "x");
        this.chunkPosZ = Ref.getter(chunkPos, "z");
        this.dataId = Ref.getter(p.setEntityData, "id");
        this.dataItems = Ref.getter(p.setEntityData, "packedItems");
        this.dataValueId = Ref.invoker(Ref.cls("net.minecraft.network.syncher.SynchedEntityData$DataValue"), "id");
        this.equipEntity = Ref.getter(p.setEquipment, "entity");
        this.equipSlots = Ref.getter(p.setEquipment, "slots");
        Class<?> pair = Ref.cls("com.mojang.datafixers.util.Pair");
        this.pairFirst = Ref.invoker(pair, "getFirst");
        this.pairSecond = Ref.invoker(pair, "getSecond");
        this.passengersVehicle = Ref.getter(p.setPassengers, "vehicle");
        this.teleportId = Ref.getter(p.teleportEntity, "id");
        this.teleportChange = Ref.getter(p.teleportEntity, "change");
        this.teleportRelatives = Ref.getter(p.teleportEntity, "relatives");
        this.teleportOnGround = Ref.getter(p.teleportEntity, "onGround");
        Class<?> pmr = Ref.cls("net.minecraft.world.entity.PositionMoveRotation");
        this.pmrPosition = Ref.getter(pmr, "position");
        this.pmrYRot = Ref.getter(pmr, "yRot");
        this.pmrXRot = Ref.getter(pmr, "xRot");
        this.syncId = Ref.getter(p.positionSync, "id");
        this.syncOnGround = Ref.getter(p.positionSync, "onGround");
        if (Ref.fieldOrNull(p.positionSync, "values") != null) {
            this.syncValues = Ref.getter(p.positionSync, "values");
            this.syncPosition = null;
            this.syncYRot = null;
            this.syncXRot = null;
            this.pathEnd = null;
        } else {
            this.syncValues = null;
            this.syncPosition = Ref.getter(p.positionSync, "position");
            this.syncYRot = Ref.getter(p.positionSync, "yRot");
            this.syncXRot = Ref.getter(p.positionSync, "xRot");
            this.pathEnd = Ref.invoker(Ref.cls("net.minecraft.world.entity.PositionPath"), "endPosition");
        }
        this.moveId = Ref.getter(p.moveEntity, "entityId");
        this.vecDeltaCodec = Ref.cls("net.minecraft.network.protocol.game.VecDeltaCodec");
        this.codecBase = Ref.getter(this.vecDeltaCodec, "base");
        if (Ref.fieldOrNull(p.moveEntity, "xa") != null) {
            this.moveXa = Ref.getter(p.moveEntity, "xa");
            this.moveYa = Ref.getter(p.moveEntity, "ya");
            this.moveZa = Ref.getter(p.moveEntity, "za");
            this.moveDelta = null;
            this.deltaDecode = null;
        } else {
            this.moveXa = null;
            this.moveYa = null;
            this.moveZa = null;
            this.moveDelta = Ref.getter(p.moveEntity, "delta");
            Class<?> vecDelta = Ref.cls("net.minecraft.network.protocol.game.VecDelta");
            this.deltaDecode = Ref.invoker(vecDelta, "decode", this.vecDeltaCodec);
        }
        this.moveYRot = Ref.getter(p.moveEntity, "yRot");
        this.moveXRot = Ref.getter(p.moveEntity, "xRot");
        this.moveHasPos = Ref.getter(p.moveEntity, "hasPos");
        this.moveHasRot = Ref.getter(p.moveEntity, "hasRot");
        this.moveOnGround = Ref.getter(p.moveEntity, "onGround");
        this.headId = Ref.getter(p.rotateHead, "entityId");
        this.headRot = Ref.getter(p.rotateHead, "yHeadRot");
        Class<?> vec3 = Ref.cls("net.minecraft.world.phys.Vec3");
        this.vec3Ctor = Ref.ctor(vec3, double.class, double.class, double.class);
        this.vecX = Ref.getter(vec3, "x");
        this.vecY = Ref.getter(vec3, "y");
        this.vecZ = Ref.getter(vec3, "z");

        this.bossId = Ref.getter(p.bossEvent, "id");
        this.bossOperation = Ref.getter(p.bossEvent, "operation");
        this.bossRemoveOperation = Ref.staticValue(p.bossEvent, "REMOVE_OPERATION");

        this.infoActions = Ref.getter(p.playerInfoUpdate, "actions");
        this.infoEntries = Ref.getter(p.playerInfoUpdate, "entries");
        Class<?> entry = Ref.cls("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
        this.entryProfileId = Ref.invoker(entry, "profileId");
        this.infoRemoveIds = Ref.getter(p.playerInfoRemove, "profileIds");
        this.infoUpdateCtor = Ref.ctor(p.playerInfoUpdate, EnumSet.class, List.class);
        this.infoActionClass = (Class<? extends Enum<?>>) Ref.cls("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");

        this.teamName = Ref.getter(p.setPlayerTeam, "name");
        this.teamMethod = Ref.getter(p.setPlayerTeam, "method");
        this.teamPlayers = Ref.getter(p.setPlayerTeam, "players");
        this.teamParameters = Ref.getter(p.setPlayerTeam, "parameters");
        this.teamCtor = Ref.ctor(p.setPlayerTeam, String.class, int.class, Optional.class, Collection.class);
        this.objectiveName = Ref.getter(p.setObjective, "objectiveName");
        this.objectiveMethod = Ref.getter(p.setObjective, "method");
        this.displaySlot = Ref.getter(p.setDisplayObjective, "slot");
        this.displayObjective = Ref.getter(p.setDisplayObjective, "objectiveName");
        this.scoreOwner = Ref.getter(p.setScore, "owner");
        this.scoreObjective = Ref.getter(p.setScore, "objectiveName");
        this.resetOwner = Ref.getter(p.resetScore, "owner");
        this.resetObjective = Ref.getter(p.resetScore, "objectiveName");

        this.gameEventType = Ref.getter(p.gameEvent, "event");
        for (String name : List.of("START_RAINING", "STOP_RAINING", "CHANGE_GAME_MODE", "RAIN_LEVEL_CHANGE",
            "THUNDER_LEVEL_CHANGE", "IMMEDIATE_RESPAWN", "LIMITED_CRAFTING")) {
            Object type = Ref.staticValue(p.gameEvent, name);
            this.gameEventTypes.put(name, type);
        }
        this.packPushId = Ref.getter(p.resourcePackPush, "id");
        this.packPopId = Ref.getter(p.resourcePackPop, "id");
        this.effectEntity = Ref.getter(p.updateMobEffect, "entityId");
        this.effectHolder = Ref.getter(p.updateMobEffect, "effect");
        this.removeEffectEntity = Ref.getter(p.removeMobEffect, "entityId");
        this.removeEffectHolder = Ref.getter(p.removeMobEffect, "effect");
    }

    // ---------------------------------------------------------------- 封包組

    public Iterable<?> subPackets(Object bundle) {
        return (Iterable<?>) this.bundleSubPackets.call(bundle);
    }

    // ---------------------------------------------------------------- 登入

    public int loginPlayerId(Object login) {
        return (int) this.loginPlayerId.get(login);
    }

    public Object respawnSpawnInfo(Object respawn) {
        return this.respawnSpawnInfo.get(respawn);
    }

    public Object spawnInfoDimension(Object spawnInfo) {
        return this.spawnInfoDimension.get(spawnInfo);
    }

    // ---------------------------------------------------------------- 實體

    public int addId(Object pk) {
        return (int) this.addId.get(pk);
    }

    public UUID addUuid(Object pk) {
        return (UUID) this.addUuid.get(pk);
    }

    public Object addType(Object pk) {
        return this.addType.get(pk);
    }

    /** {x, y, z, yRot, xRot, headRot} */
    public double[] addPosition(Object pk) {
        return new double[]{
            (double) this.addX.get(pk), (double) this.addY.get(pk), (double) this.addZ.get(pk),
            byteToDeg((byte) this.addYRot.get(pk)), byteToDeg((byte) this.addXRot.get(pk)), byteToDeg((byte) this.addHeadRot.get(pk))
        };
    }

    public int[] removeIds(Object pk) {
        Object list = this.removeIds.get(pk);
        if (list instanceof it.unimi.dsi.fastutil.ints.IntList il) {
            return il.toIntArray();
        }
        List<?> l = (List<?>) list;
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) l.get(i)).intValue();
        }
        return out;
    }

    public long chunkKey(Object chunkPacket) {
        return key((int) this.chunkX.get(chunkPacket), (int) this.chunkZ.get(chunkPacket));
    }

    public int chunkX(Object chunkPacket) {
        return (int) this.chunkX.get(chunkPacket);
    }

    public int chunkZ(Object chunkPacket) {
        return (int) this.chunkZ.get(chunkPacket);
    }

    public long forgetKey(Object forget) {
        Object pos = this.forgetPos.get(forget);
        return key((int) this.chunkPosX.get(pos), (int) this.chunkPosZ.get(pos));
    }

    public static long key(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >>> 32);
    }

    public int dataEntity(Object pk) {
        return (int) this.dataId.get(pk);
    }

    public List<?> dataItems(Object pk) {
        return (List<?>) this.dataItems.get(pk);
    }

    public int dataValueId(Object dataValue) {
        return (int) this.dataValueId.call(dataValue);
    }

    public Object buildEntityData(int entityId, List<Object> values) {
        return Ref.buildRecord(this.p.setEntityData, Map.of("id", entityId, "packedItems", values));
    }

    public int equipEntity(Object pk) {
        return (int) this.equipEntity.get(pk);
    }

    /** 回傳 [裝備欄位, 物品] 的清單。 */
    public List<Object[]> equipSlots(Object pk) {
        List<?> pairs = (List<?>) this.equipSlots.get(pk);
        List<Object[]> out = new ArrayList<>(pairs.size());
        for (Object pair : pairs) {
            out.add(new Object[]{this.pairFirst.call(pair), this.pairSecond.call(pair)});
        }
        return out;
    }

    public int passengersVehicle(Object pk) {
        return (int) this.passengersVehicle.get(pk);
    }

    public int teleportId(Object pk) {
        return (int) this.teleportId.get(pk);
    }

    /** 套用傳送封包到位置 {x, y, z, yRot, xRot}；relatives 中有的軸用相對值。 */
    public void applyTeleport(Object pk, double[] pos) {
        Object change = this.teleportChange.get(pk);
        Object vec = this.pmrPosition.get(change);
        Set<?> rel = (Set<?>) this.teleportRelatives.get(pk);
        double x = (double) this.vecX.get(vec);
        double y = (double) this.vecY.get(vec);
        double z = (double) this.vecZ.get(vec);
        float yRot = (float) this.pmrYRot.get(change);
        float xRot = (float) this.pmrXRot.get(change);
        boolean rx = false, ry = false, rz = false, ryr = false, rxr = false;
        for (Object r : rel) {
            switch (((Enum<?>) r).name()) {
                case "X" -> rx = true;
                case "Y" -> ry = true;
                case "Z" -> rz = true;
                case "Y_ROT" -> ryr = true;
                case "X_ROT" -> rxr = true;
                default -> {
                }
            }
        }
        pos[0] = rx ? pos[0] + x : x;
        pos[1] = ry ? pos[1] + y : y;
        pos[2] = rz ? pos[2] + z : z;
        pos[3] = ryr ? pos[3] + yRot : yRot;
        pos[4] = rxr ? pos[4] + xRot : xRot;
    }

    public boolean teleportOnGround(Object pk) {
        return (boolean) this.teleportOnGround.get(pk);
    }

    public int syncId(Object pk) {
        return (int) this.syncId.get(pk);
    }

    /** 位置同步封包 → {x, y, z, yRot, xRot}。 */
    public double[] syncPosition(Object pk) {
        if (this.syncValues != null) {
            Object v = this.syncValues.get(pk);
            Object vec = this.pmrPosition.get(v);
            return new double[]{(double) this.vecX.get(vec), (double) this.vecY.get(vec), (double) this.vecZ.get(vec),
                (float) this.pmrYRot.get(v), (float) this.pmrXRot.get(v)};
        }
        Object vec = this.pathEnd.call(this.syncPosition.get(pk));
        return new double[]{(double) this.vecX.get(vec), (double) this.vecY.get(vec), (double) this.vecZ.get(vec),
            (float) this.syncYRot.get(pk), (float) this.syncXRot.get(pk)};
    }

    public boolean syncOnGround(Object pk) {
        return (boolean) this.syncOnGround.get(pk);
    }

    public int moveId(Object pk) {
        return (int) this.moveId.get(pk);
    }

    /** 套用相對移動封包到位置 {x, y, z, yRot, xRot}，回傳 onGround。 */
    public boolean applyMove(Object pk, double[] pos) {
        if ((boolean) this.moveHasPos.get(pk)) {
            if (this.moveXa != null) {
                pos[0] = decodeDelta(pos[0], (short) this.moveXa.get(pk));
                pos[1] = decodeDelta(pos[1], (short) this.moveYa.get(pk));
                pos[2] = decodeDelta(pos[2], (short) this.moveZa.get(pk));
            } else {
                Object codec;
                try {
                    codec = this.vecDeltaCodec.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new NmsException("無法建立 VecDeltaCodec");
                }
                Ref.write(codec, "base", Ref.construct(this.vec3Ctor, pos[0], pos[1], pos[2]));
                Object path = this.deltaDecode.call(this.moveDelta.get(pk), codec);
                Object end = Ref.call(path, "endPosition");
                pos[0] = (double) this.vecX.get(end);
                pos[1] = (double) this.vecY.get(end);
                pos[2] = (double) this.vecZ.get(end);
            }
        }
        if ((boolean) this.moveHasRot.get(pk)) {
            pos[3] = byteToDeg((byte) this.moveYRot.get(pk));
            pos[4] = byteToDeg((byte) this.moveXRot.get(pk));
        }
        return (boolean) this.moveOnGround.get(pk);
    }

    private static double decodeDelta(double base, short delta) {
        if (delta == 0) {
            return base;
        }
        return (Math.round(base * 4096.0) + delta) / 4096.0;
    }

    public int headId(Object pk) {
        return (int) this.headId.get(pk);
    }

    public float headRot(Object pk) {
        return byteToDeg((byte) this.headRot.get(pk));
    }

    public static float byteToDeg(byte b) {
        return b * 360.0f / 256.0f;
    }

    // ---------------------------------------------------------------- 王者血條

    public UUID bossId(Object pk) {
        return (UUID) this.bossId.get(pk);
    }

    public Object bossOperation(Object pk) {
        return this.bossOperation.get(pk);
    }

    public boolean isBossRemove(Object pk) {
        return this.bossOperation.get(pk) == this.bossRemoveOperation;
    }

    public boolean isBossAdd(Object pk) {
        return this.bossOperation.get(pk).getClass().getSimpleName().contains("Add");
    }

    // ---------------------------------------------------------------- 玩家列表

    public Set<?> infoActions(Object pk) {
        return (Set<?>) this.infoActions.get(pk);
    }

    public List<?> infoEntries(Object pk) {
        return (List<?>) this.infoEntries.get(pk);
    }

    public UUID entryProfileId(Object entry) {
        return (UUID) this.entryProfileId.call(entry);
    }

    public List<?> infoRemoveIds(Object pk) {
        return (List<?>) this.infoRemoveIds.get(pk);
    }

    /** 依動作把新的玩家列表項目合併進舊的。 */
    public Object mergeEntry(Object old, Object incoming, Set<?> actions) {
        Map<String, Object> overrides = new HashMap<>();
        for (Object action : actions) {
            String component = switch (((Enum<?>) action).name()) {
                case "INITIALIZE_CHAT" -> "chatSession";
                case "UPDATE_GAME_MODE" -> "gameMode";
                case "UPDATE_LISTED" -> "listed";
                case "UPDATE_LATENCY" -> "latency";
                case "UPDATE_DISPLAY_NAME" -> "displayName";
                case "UPDATE_LIST_ORDER" -> "listOrder";
                case "UPDATE_HAT" -> "showHat";
                default -> null;
            };
            if (component != null && Ref.hasComponent(old.getClass(), component)) {
                overrides.put(component, Ref.component(incoming, component));
            }
        }
        return overrides.isEmpty() ? old : Ref.copyRecord(old, overrides);
    }

    /** 建立「新增所有玩家」的封包（不含聊天簽章資訊）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object buildPlayerInfo(Collection<Object> entries) {
        EnumSet actions = EnumSet.noneOf((Class) this.infoActionClass);
        for (Enum<?> a : this.infoActionClass.getEnumConstants()) {
            if (!a.name().equals("INITIALIZE_CHAT")) {
                actions.add(a);
            }
        }
        // 移除聊天簽章資訊，避免回放端驗證
        List<Object> cleaned = new ArrayList<>(entries.size());
        for (Object e : entries) {
            if (Ref.hasComponent(e.getClass(), "chatSession") && Ref.component(e, "chatSession") != null) {
                Map<String, Object> o = new HashMap<>();
                o.put("chatSession", null);
                cleaned.add(Ref.copyRecord(e, o));
            } else {
                cleaned.add(e);
            }
        }
        return Ref.construct(this.infoUpdateCtor, actions, cleaned);
    }

    // ---------------------------------------------------------------- 計分板

    public String teamName(Object pk) {
        return (String) this.teamName.get(pk);
    }

    public int teamMethod(Object pk) {
        return (int) this.teamMethod.get(pk);
    }

    public Collection<?> teamPlayers(Object pk) {
        return (Collection<?>) this.teamPlayers.get(pk);
    }

    public Optional<?> teamParameters(Object pk) {
        return (Optional<?>) this.teamParameters.get(pk);
    }

    /** method 0 = 新增隊伍（含參數與成員）。 */
    public Object buildTeamAdd(String name, Optional<?> parameters, Collection<String> players) {
        return Ref.construct(this.teamCtor, name, 0, parameters, List.copyOf(players));
    }

    public String objectiveName(Object pk) {
        return (String) this.objectiveName.get(pk);
    }

    public int objectiveMethod(Object pk) {
        return (int) this.objectiveMethod.get(pk);
    }

    public Object displaySlot(Object pk) {
        return this.displaySlot.get(pk);
    }

    public String displayObjective(Object pk) {
        return (String) this.displayObjective.get(pk);
    }

    public String scoreOwner(Object pk) {
        return (String) this.scoreOwner.get(pk);
    }

    public String scoreObjective(Object pk) {
        return (String) this.scoreObjective.get(pk);
    }

    public String resetOwner(Object pk) {
        return (String) this.resetOwner.get(pk);
    }

    public String resetObjective(Object pk) {
        return (String) this.resetObjective.get(pk);
    }

    // ---------------------------------------------------------------- 其他

    /** 天氣等遊戲事件的種類名稱；不需要快取的回傳 null。 */
    public String gameEventName(Object pk) {
        Object type = this.gameEventType.get(pk);
        for (Map.Entry<String, Object> e : this.gameEventTypes.entrySet()) {
            if (e.getValue() == type) {
                return e.getKey();
            }
        }
        return null;
    }

    public UUID packPushId(Object pk) {
        return (UUID) this.packPushId.get(pk);
    }

    public Optional<?> packPopId(Object pk) {
        return (Optional<?>) this.packPopId.get(pk);
    }

    public int effectEntity(Object pk) {
        return (int) this.effectEntity.get(pk);
    }

    public Object effectHolder(Object pk) {
        return this.effectHolder.get(pk);
    }

    public int removeEffectEntity(Object pk) {
        return (int) this.removeEffectEntity.get(pk);
    }

    public Object removeEffectHolder(Object pk) {
        return this.removeEffectHolder.get(pk);
    }

    /** 用來呼叫不在快取欄位裡的方法。 */
    public static Object invoke(Object target, Method m, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new NmsException("呼叫失敗：" + Ref.rootMessage(e));
        }
    }
}
