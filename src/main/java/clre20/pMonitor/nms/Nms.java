package clre20.pMonitor.nms;

import com.google.common.collect.ImmutableList;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 伺服器內部（NMS）存取的集中處。啟動時一次解析所有需要的類別與成員，
 * 找不到時 {@link #init()} 會丟出例外，外部據此停用錄製功能。
 */
public final class Nms {

    private static Nms instance;

    public static Nms get() {
        return instance;
    }

    public static boolean ready() {
        return instance != null;
    }

    /** 初始化；失敗時回傳錯誤訊息，成功回傳 null。 */
    public static String init() {
        try {
            instance = new Nms();
            return null;
        } catch (Throwable t) {
            instance = null;
            return t instanceof NmsException ? t.getMessage() : Ref.rootMessage(t);
        }
    }

    public final PacketClasses packets;
    public final PacketFields fields;

    // ---- 玩家與連線
    private final Ref.Invoker craftPlayerHandle;
    private final Ref.Getter playerConnection;
    private final Ref.Getter listenerConnection;
    private final Ref.Getter connectionChannel;
    private final Class<?> packetEncoderClass;
    private final Ref.Getter encoderProtocol;
    private final Ref.Invoker protocolId;
    private final Ref.Invoker protocolCodec;
    private final Ref.Fast streamEncode;
    private final Object configCodec;

    // ---- 伺服器
    private final Object server;
    private final Ref.Invoker serverRegistries;
    private final Constructor<?> syncRegistriesTask;
    private final Ref.Invoker syncHandleResponse;
    private final Ref.Invoker serverWorldData;
    private final Ref.Invoker worldDataFeatures;
    private final Object featureRegistry;
    private final Ref.Invoker featureToNames;
    private final Constructor<?> enabledFeaturesPacket;
    private final Constructor<?> resourcePackPop;

    // ---- 實體
    private final Ref.Invoker entityLevel;
    private final Ref.Fast entityGetId;
    private final Ref.Fast entityX;
    private final Ref.Fast entityY;
    private final Ref.Fast entityZ;
    private final Ref.Fast entityYRot;
    private final Ref.Fast entityXRot;
    private final Ref.Fast entityHeadRot;
    private final Ref.Fast entityOnGround;
    private final Ref.Invoker entityDelta;
    private final Ref.Getter vecX;
    private final Ref.Getter vecY;
    private final Ref.Getter vecZ;
    private final Ref.Fast levelGetEntity;
    private final Ref.Invoker levelDimension;
    private final Ref.Invoker resourceKeyId;
    private final Ref.Invoker levelChunkSource;
    private final Ref.Getter chunkSourceChunkMap;
    private final Ref.Getter chunkMapEntityMap;
    private final Ref.Getter trackedServerEntity;
    private final Ref.Getter trackedSeenBy;
    private final Ref.Invoker serverLevelKeys;
    private final Ref.Invoker createPlayerInitializing;
    private final Ref.Invoker sendPairingData;
    private final Ref.Invoker getChunkNow;
    private final Ref.Invoker levelLightEngine;
    private final Constructor<?> chunkPacketCtor;
    private final boolean chunkPacketHasPaperFlag;

    // ---- 玩家本身
    private final Ref.Invoker createSpawnInfo;
    private final Ref.Invoker getGameProfile;
    private final Object gameProfileCodec;
    private final Ref.Getter playerGameMode;
    private final Ref.Invoker gameModeForPlayer;
    private final Ref.Invoker gameTypeId;
    private final Ref.Invoker playerInventory;
    private final Ref.Invoker inventoryGetItem;
    private final Ref.Invoker itemCopy;
    private final Ref.Invoker itemMatches;
    private final Object[] equipmentSlots;
    private final Ref.Invoker getItemBySlot;
    private final Ref.Invoker pairOf;
    private final Constructor<?> setEquipmentCtor;
    private final Constructor<?> setPlayerInventoryCtor;
    private final Constructor<?> setHeldSlotCtor;
    private final Constructor<?> blockPosCtor;
    private final Constructor<?> blockDestructionCtor;
    private final Ref.Getter destroyPos;
    private final Ref.Getter lastSentState;
    private final Ref.Getter isDestroying;
    private final Ref.Invoker blockPosX;
    private final Ref.Invoker blockPosY;
    private final Ref.Invoker blockPosZ;
    private final Constructor<?> levelEventCtor;
    private final Ref.Invoker blockGetId;
    private final Ref.Invoker craftBlockDataState;
    private final Ref.Invoker stateSoundType;
    private final Ref.Invoker soundTypePlace;
    private final Ref.Invoker soundTypeVolume;
    private final Ref.Invoker soundTypePitch;
    private final Ref.Invoker wrapSoundHolder;
    private final Object soundEventRegistry;
    private final Object soundSourceBlocks;
    private final Constructor<?> soundPacketCtor;
    private final Ref.Invoker effectsOf;

    // ---- 揮手（版本不同）
    private final Swing swing;

    // ---- 聊天
    private final Ref.Getter chatBody;
    private final Ref.Invoker bodyContent;
    private final Ref.Getter chatUnsigned;
    private final Ref.Getter chatType;
    private final Ref.Invoker chatTypeDecorate;
    private final Ref.Invoker componentLiteral;
    private final Constructor<?> systemChatCtor;

    private Nms() {
        this.packets = new PacketClasses();
        this.fields = new PacketFields(this.packets);

        Class<?> craftPlayer = Ref.cls("org.bukkit.craftbukkit.entity.CraftPlayer");
        Class<?> serverPlayer = Ref.cls("net.minecraft.server.level.ServerPlayer");
        Class<?> commonListener = Ref.cls("net.minecraft.server.network.ServerCommonPacketListenerImpl");
        Class<?> connection = Ref.cls("net.minecraft.network.Connection");
        this.craftPlayerHandle = Ref.invoker(craftPlayer, "getHandle");
        this.playerConnection = Ref.getter(serverPlayer, "connection");
        this.listenerConnection = Ref.getter(commonListener, "connection");
        this.connectionChannel = Ref.getter(connection, "channel");
        this.packetEncoderClass = Ref.cls("net.minecraft.network.PacketEncoder");
        this.encoderProtocol = Ref.getter(this.packetEncoderClass, "protocolInfo");
        Class<?> protocolInfo = Ref.cls("net.minecraft.network.ProtocolInfo");
        this.protocolId = Ref.invoker(protocolInfo, "id");
        this.protocolCodec = Ref.invoker(protocolInfo, "codec");
        Class<?> streamEncoder = Ref.cls("net.minecraft.network.codec.StreamEncoder");
        this.streamEncode = Ref.fast(Ref.methodByArity(streamEncoder, "encode", 2), void.class, 2);
        Object configProtocol = Ref.staticValue(Ref.cls("net.minecraft.network.protocol.configuration.ConfigurationProtocols"), "CLIENTBOUND");
        this.configCodec = this.protocolCodec.call(configProtocol);

        // 伺服器
        Class<?> craftServer = Ref.cls("org.bukkit.craftbukkit.CraftServer");
        this.server = Ref.invoker(craftServer, "getServer").call(Bukkit.getServer());
        Class<?> minecraftServer = Ref.cls("net.minecraft.server.MinecraftServer");
        Class<?> layered = Ref.cls("net.minecraft.core.LayeredRegistryAccess");
        this.serverRegistries = Ref.invoker(minecraftServer, "registries");
        Class<?> syncTask = Ref.cls("net.minecraft.server.network.config.SynchronizeRegistriesTask");
        this.syncRegistriesTask = Ref.ctor(syncTask, List.class, layered);
        this.syncHandleResponse = Ref.invoker(syncTask, "handleResponse", List.class, Consumer.class);
        this.serverWorldData = Ref.invoker(minecraftServer, "getWorldData");
        Class<?> worldData = Ref.cls("net.minecraft.world.level.storage.WorldData");
        this.worldDataFeatures = Ref.invoker(worldData, "enabledFeatures");
        Class<?> featureFlags = Ref.cls("net.minecraft.world.flag.FeatureFlags");
        this.featureRegistry = Ref.staticValue(featureFlags, "REGISTRY");
        Class<?> featureFlagSet = Ref.cls("net.minecraft.world.flag.FeatureFlagSet");
        this.featureToNames = Ref.invoker(this.featureRegistry.getClass(), "toNames", featureFlagSet);
        this.enabledFeaturesPacket = Ref.ctor(Ref.cls("net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket"), Set.class);
        this.resourcePackPop = Ref.ctor(this.packets.resourcePackPop, Optional.class);

        // 實體
        Class<?> entity = Ref.cls("net.minecraft.world.entity.Entity");
        Class<?> serverLevel = Ref.cls("net.minecraft.server.level.ServerLevel");
        Class<?> level = Ref.cls("net.minecraft.world.level.Level");
        this.entityLevel = Ref.invoker(entity, "level");
        this.entityGetId = Ref.fast(entity, "getId", int.class);
        this.entityX = Ref.fast(entity, "getX", double.class);
        this.entityY = Ref.fast(entity, "getY", double.class);
        this.entityZ = Ref.fast(entity, "getZ", double.class);
        this.entityYRot = Ref.fast(entity, "getYRot", float.class);
        this.entityXRot = Ref.fast(entity, "getXRot", float.class);
        this.entityHeadRot = Ref.fast(entity, "getYHeadRot", float.class);
        this.entityOnGround = Ref.fast(entity, "onGround", boolean.class);
        this.entityDelta = Ref.invoker(entity, "getDeltaMovement");
        Class<?> vec3 = Ref.cls("net.minecraft.world.phys.Vec3");
        this.vecX = Ref.getter(vec3, "x");
        this.vecY = Ref.getter(vec3, "y");
        this.vecZ = Ref.getter(vec3, "z");
        this.levelGetEntity = Ref.fast(serverLevel, "getEntity", Object.class, int.class);
        this.levelDimension = Ref.invoker(level, "dimension");
        Class<?> resourceKey = Ref.cls("net.minecraft.resources.ResourceKey");
        Method idMethod = Ref.methodOrNull(resourceKey, "identifier");
        if (idMethod == null) {
            idMethod = Ref.method(resourceKey, "location");
        }
        this.resourceKeyId = Ref.invoker(idMethod);
        this.levelChunkSource = Ref.invoker(serverLevel, "getChunkSource");
        Class<?> serverChunkCache = Ref.cls("net.minecraft.server.level.ServerChunkCache");
        Class<?> chunkMap = Ref.cls("net.minecraft.server.level.ChunkMap");
        this.chunkSourceChunkMap = Ref.getter(serverChunkCache, "chunkMap");
        this.chunkMapEntityMap = Ref.getter(chunkMap, "entityMap");
        Class<?> trackedEntity = Ref.cls("net.minecraft.server.level.ChunkMap$TrackedEntity");
        this.trackedServerEntity = Ref.getter(trackedEntity, "serverEntity");
        this.trackedSeenBy = Ref.getter(trackedEntity, "seenBy");
        this.serverLevelKeys = Ref.invoker(minecraftServer, "levelKeys");
        this.createPlayerInitializing = Ref.invoker(this.packets.playerInfoUpdate, "createPlayerInitializing", Collection.class);
        Class<?> serverEntity = Ref.cls("net.minecraft.server.level.ServerEntity");
        this.sendPairingData = Ref.invoker(serverEntity, "sendPairingData", serverPlayer, Consumer.class);
        this.getChunkNow = Ref.invoker(serverChunkCache, "getChunkNow", int.class, int.class);
        this.levelLightEngine = Ref.invoker(level, "getLightEngine");
        Class<?> levelChunk = Ref.cls("net.minecraft.world.level.chunk.LevelChunk");
        Class<?> lightEngine = Ref.cls("net.minecraft.world.level.lighting.LevelLightEngine");
        Constructor<?> paperCtor = Ref.ctorOrNull(this.packets.chunkWithLight, levelChunk, lightEngine, BitSet.class, BitSet.class, boolean.class);
        if (paperCtor != null) {
            this.chunkPacketCtor = paperCtor;
            this.chunkPacketHasPaperFlag = true;
        } else {
            this.chunkPacketCtor = Ref.ctor(this.packets.chunkWithLight, levelChunk, lightEngine, BitSet.class, BitSet.class);
            this.chunkPacketHasPaperFlag = false;
        }

        // 玩家本身
        this.createSpawnInfo = Ref.invoker(serverPlayer, "createCommonSpawnInfo", serverLevel);
        Class<?> playerClass = Ref.cls("net.minecraft.world.entity.player.Player");
        this.getGameProfile = Ref.invoker(playerClass, "getGameProfile");
        this.gameProfileCodec = Ref.staticValue(Ref.cls("net.minecraft.network.codec.ByteBufCodecs"), "GAME_PROFILE");
        this.playerGameMode = Ref.getter(serverPlayer, "gameMode");
        Class<?> serverPlayerGameMode = Ref.cls("net.minecraft.server.level.ServerPlayerGameMode");
        this.gameModeForPlayer = Ref.invoker(serverPlayerGameMode, "getGameModeForPlayer");
        this.gameTypeId = Ref.invoker(Ref.cls("net.minecraft.world.level.GameType"), "getId");
        this.playerInventory = Ref.invoker(playerClass, "getInventory");
        Class<?> inventory = Ref.cls("net.minecraft.world.entity.player.Inventory");
        this.inventoryGetItem = Ref.invoker(inventory, "getItem", int.class);
        Class<?> itemStack = Ref.cls("net.minecraft.world.item.ItemStack");
        this.itemCopy = Ref.invoker(itemStack, "copy");
        this.itemMatches = Ref.invoker(itemStack, "matches", itemStack, itemStack);
        Class<?> equipmentSlot = Ref.cls("net.minecraft.world.entity.EquipmentSlot");
        this.equipmentSlots = equipmentSlot.getEnumConstants();
        Class<?> livingEntity = Ref.cls("net.minecraft.world.entity.LivingEntity");
        this.getItemBySlot = Ref.invoker(livingEntity, "getItemBySlot", equipmentSlot);
        Class<?> pair = Ref.cls("com.mojang.datafixers.util.Pair");
        this.pairOf = Ref.invoker(pair, "of", Object.class, Object.class);
        this.setEquipmentCtor = Ref.ctor(this.packets.setEquipment, int.class, List.class);
        this.setPlayerInventoryCtor = Ref.ctor(Ref.cls("net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket"), int.class, itemStack);
        this.setHeldSlotCtor = Ref.ctor(Ref.cls("net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket"), int.class);
        Class<?> blockPos = Ref.cls("net.minecraft.core.BlockPos");
        this.blockPosCtor = Ref.ctor(blockPos, int.class, int.class, int.class);
        Class<?> vec3i = Ref.cls("net.minecraft.core.Vec3i");
        this.blockPosX = Ref.invoker(vec3i, "getX");
        this.blockPosY = Ref.invoker(vec3i, "getY");
        this.blockPosZ = Ref.invoker(vec3i, "getZ");
        this.blockDestructionCtor = Ref.ctor(Ref.cls("net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket"), int.class, blockPos, int.class);
        this.destroyPos = Ref.getter(serverPlayerGameMode, "destroyPos");
        this.lastSentState = Ref.getter(serverPlayerGameMode, "lastSentState");
        this.isDestroying = Ref.getter(serverPlayerGameMode, "isDestroyingBlock");
        this.levelEventCtor = Ref.ctor(Ref.cls("net.minecraft.network.protocol.game.ClientboundLevelEventPacket"), int.class, blockPos, int.class, boolean.class);
        Class<?> block = Ref.cls("net.minecraft.world.level.block.Block");
        Class<?> blockState = Ref.cls("net.minecraft.world.level.block.state.BlockState");
        this.blockGetId = Ref.invoker(block, "getId", blockState);
        this.craftBlockDataState = Ref.invoker(Ref.cls("org.bukkit.craftbukkit.block.data.CraftBlockData"), "getState");
        Class<?> blockStateBase = Ref.cls("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
        this.stateSoundType = Ref.invoker(blockStateBase, "getSoundType");
        Class<?> soundType = Ref.cls("net.minecraft.world.level.block.SoundType");
        this.soundTypePlace = Ref.invoker(soundType, "getPlaceSound");
        this.soundTypeVolume = Ref.invoker(soundType, "getVolume");
        this.soundTypePitch = Ref.invoker(soundType, "getPitch");
        Class<?> builtIn = Ref.cls("net.minecraft.core.registries.BuiltInRegistries");
        this.soundEventRegistry = Ref.staticValue(builtIn, "SOUND_EVENT");
        Class<?> registry = Ref.cls("net.minecraft.core.Registry");
        this.wrapSoundHolder = Ref.invoker(registry, "wrapAsHolder", Object.class);
        Class<?> soundSource = Ref.cls("net.minecraft.sounds.SoundSource");
        Object blocks = null;
        for (Object c : soundSource.getEnumConstants()) {
            if (((Enum<?>) c).name().equals("BLOCKS")) {
                blocks = c;
            }
        }
        this.soundSourceBlocks = blocks;
        Class<?> holder = Ref.cls("net.minecraft.core.Holder");
        this.soundPacketCtor = Ref.ctor(Ref.cls("net.minecraft.network.protocol.game.ClientboundSoundPacket"),
            holder, soundSource, double.class, double.class, double.class, float.class, float.class, long.class);
        this.effectsOf = Ref.invoker(livingEntity, "getActiveEffects");

        this.swing = new Swing(this.packets, livingEntity, entity);

        // 聊天轉換
        this.chatBody = Ref.getter(this.packets.playerChat, "body");
        this.bodyContent = Ref.invoker(Ref.cls("net.minecraft.network.chat.SignedMessageBody$Packed"), "content");
        this.chatUnsigned = Ref.getter(this.packets.playerChat, "unsignedContent");
        this.chatType = Ref.getter(this.packets.playerChat, "chatType");
        Class<?> component = Ref.cls("net.minecraft.network.chat.Component");
        this.chatTypeDecorate = Ref.invoker(Ref.cls("net.minecraft.network.chat.ChatType$Bound"), "decorate", component);
        this.componentLiteral = Ref.invoker(component, "literal", String.class);
        this.systemChatCtor = Ref.ctor(this.packets.systemChat, component, boolean.class);
    }

    // ================================================================ 連線

    public Object handle(Player player) {
        return this.craftPlayerHandle.call(player);
    }

    public Channel channel(Player player) {
        Object listener = this.playerConnection.get(handle(player));
        if (listener == null) {
            return null;
        }
        Object connection = this.listenerConnection.get(listener);
        return connection == null ? null : (Channel) this.connectionChannel.get(connection);
    }

    /** 從管線上的編碼器取得目前的協定（PLAY / CONFIGURATION…）；取不到回傳 null。 */
    public Object protocolOf(Object encoderHandler) {
        if (encoderHandler == null || !this.packetEncoderClass.isInstance(encoderHandler)) {
            return null;
        }
        return this.encoderProtocol.get(encoderHandler);
    }

    public String protocolName(Object protocolInfo) {
        return ((Enum<?>) this.protocolId.call(protocolInfo)).name();
    }

    public Object codecOf(Object protocolInfo) {
        return this.protocolCodec.call(protocolInfo);
    }

    public Object configCodec() {
        return this.configCodec;
    }

    /** 以指定的編解碼器把封包編碼成位元組（封包 ID + 內容，與 Flashback 存檔格式相同）。 */
    public byte[] encode(Object codec, Object packet) {
        ByteBuf buf = Unpooled.buffer(256);
        try {
            this.streamEncode.run2(codec, buf, packet);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    // ================================================================ 設定階段（快照用）

    /** 產生快照用的設定階段封包：功能旗標、完整註冊表、標籤。 */
    /** 設定階段封包的快取：註冊表內容只有在重新載入資料包時才會變（見 {@link #clearConfigurationCache()}）。 */
    private volatile List<Object> configurationCache;

    public void clearConfigurationCache() {
        this.configurationCache = null;
    }

    /** 快照用的設定階段封包（快取）；封包內容不會被修改，可以重複寫入多個錄製檔。 */
    public List<Object> configurationPackets() {
        List<Object> cached = this.configurationCache;
        if (cached == null) {
            cached = List.copyOf(buildConfigurationPackets());
            this.configurationCache = cached;
        }
        return cached;
    }

    private List<Object> buildConfigurationPackets() {
        List<Object> out = new ArrayList<>();
        Object features = this.worldDataFeatures.call(this.serverWorldData.call(this.server));
        Object names = this.featureToNames.call(this.featureRegistry, features);
        out.add(Ref.construct(this.enabledFeaturesPacket, names));
        Object registries = this.serverRegistries.call(this.server);
        Object task = Ref.construct(this.syncRegistriesTask, List.of(), registries);
        // 不協商「已知資源包」，讓伺服器送出完整的註冊表內容
        this.syncHandleResponse.call(task, List.of(), (Consumer<Object>) out::add);
        return out;
    }

    public Object resourcePackPopAll() {
        return Ref.construct(this.resourcePackPop, Optional.empty());
    }

    // ================================================================ 實體與世界

    public Object level(Object entity) {
        return this.entityLevel.call(entity);
    }

    public Object level(World world) {
        return Ref.call(world, "getHandle");
    }

    public int entityId(Object entity) {
        return this.entityGetId.getInt(entity);
    }

    public Object entityById(Object level, int id) {
        return this.levelGetEntity.callInt(level, id);
    }

    public double x(Object e) {
        return this.entityX.getDouble(e);
    }

    public double y(Object e) {
        return this.entityY.getDouble(e);
    }

    public double z(Object e) {
        return this.entityZ.getDouble(e);
    }

    public float yRot(Object e) {
        return this.entityYRot.getFloat(e);
    }

    public float xRot(Object e) {
        return this.entityXRot.getFloat(e);
    }

    public float headRot(Object e) {
        return this.entityHeadRot.getFloat(e);
    }

    public boolean onGround(Object e) {
        return this.entityOnGround.getBoolean(e);
    }

    public double[] velocity(Object e) {
        Object v = this.entityDelta.call(e);
        return new double[]{(double) this.vecX.get(v), (double) this.vecY.get(v), (double) this.vecZ.get(v)};
    }

    public double vecX(Object v) {
        return (double) this.vecX.get(v);
    }

    public double vecY(Object v) {
        return (double) this.vecY.get(v);
    }

    public double vecZ(Object v) {
        return (double) this.vecZ.get(v);
    }

    /** 世界的維度識別碼，例如 minecraft:overworld。 */
    public String dimensionId(Object level) {
        return this.resourceKeyId.call(this.levelDimension.call(level)).toString();
    }

    public String resourceKeyId(Object resourceKey) {
        return this.resourceKeyId.call(resourceKey).toString();
    }

    /** 取得實體目前的配對資料（新增實體、資料、裝備、乘客等），跟伺服器對新觀看者送出的一樣。 */
    public List<Object> pairingPackets(Object level, Object viewer, int entityId) {
        Object chunkSource = this.levelChunkSource.call(level);
        Object chunkMap = this.chunkSourceChunkMap.get(chunkSource);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> entityMap = (Map<Integer, Object>) this.chunkMapEntityMap.get(chunkMap);
        Object tracked = entityMap.get(entityId);
        if (tracked == null) {
            return null;
        }
        Object serverEntity = this.trackedServerEntity.get(tracked);
        List<Object> out = new ArrayList<>();
        this.sendPairingData.call(serverEntity, viewer, (Consumer<Object>) out::add);
        return out;
    }

    /** 依目前伺服器上的區塊重新產生區塊封包；區塊未載入時回傳 null。 */
    public Object chunkPacket(Object level, int x, int z) {
        Object chunkSource = this.levelChunkSource.call(level);
        Object chunk = this.getChunkNow.call(chunkSource, x, z);
        if (chunk == null) {
            return null;
        }
        Object light = this.levelLightEngine.call(level);
        if (this.chunkPacketHasPaperFlag) {
            // false：不經過 Paper 反礦物透視處理，封包立即可用
            return Ref.construct(this.chunkPacketCtor, chunk, light, null, null, false);
        }
        return Ref.construct(this.chunkPacketCtor, chunk, light, null, null);
    }

    public boolean isChunkLoaded(Object level, int x, int z) {
        return this.getChunkNow.call(this.levelChunkSource.call(level), x, z) != null;
    }

    /** 伺服器端目前讓這位玩家看得到的實體 ID。 */
    public List<Integer> trackedIdsSeenBy(Object level, Object serverPlayer) {
        Object listener = this.playerConnection.get(serverPlayer);
        Object chunkMap = this.chunkSourceChunkMap.get(this.levelChunkSource.call(level));
        @SuppressWarnings("unchecked")
        Map<Integer, Object> entityMap = (Map<Integer, Object>) this.chunkMapEntityMap.get(chunkMap);
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Object> e : new ArrayList<>(entityMap.entrySet())) {
            Set<?> seenBy = (Set<?>) this.trackedSeenBy.get(e.getValue());
            if (seenBy != null && seenBy.contains(listener)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 沒有快取到登入封包時（插件中途載入），用伺服器設定建立一個。 */
    public Object fallbackLogin(Object serverPlayer, Object spawnInfo) {
        Map<String, Object> v = new java.util.HashMap<>();
        v.put("playerId", entityId(serverPlayer));
        v.put("hardcore", Bukkit.isHardcore());
        v.put("levels", this.serverLevelKeys.call(this.server));
        v.put("maxPlayers", Bukkit.getMaxPlayers());
        v.put("chunkRadius", Bukkit.getViewDistance());
        v.put("simulationDistance", Bukkit.getSimulationDistance());
        v.put("reducedDebugInfo", false);
        v.put("showDeathScreen", true);
        v.put("doLimitedCrafting", false);
        v.put("commonPlayerSpawnInfo", spawnInfo);
        v.put("onlineMode", Bukkit.getOnlineMode());
        v.put("enforcesSecureChat", false);
        return Ref.buildRecord(this.packets.login, v);
    }

    /** 所有線上玩家的玩家列表封包（插件中途載入時使用）。 */
    public Object playerInfoAllOnline() {
        List<Object> handles = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            handles.add(handle(p));
        }
        return this.createPlayerInitializing.call(null, handles);
    }

    // ================================================================ 玩家本身

    public Object spawnInfo(Object serverPlayer) {
        return this.createSpawnInfo.call(serverPlayer, level(serverPlayer));
    }

    public Object gameProfile(Object serverPlayer) {
        return this.getGameProfile.call(serverPlayer);
    }

    public void encodeGameProfile(ByteBuf buf, Object profile) {
        this.streamEncode.run2(this.gameProfileCodec, buf, profile);
    }

    public int gameModeId(Object serverPlayer) {
        Object gm = this.playerGameMode.get(serverPlayer);
        return (int) this.gameTypeId.call(this.gameModeForPlayer.call(gm));
    }

    public Object hotbarItem(Object serverPlayer, int slot) {
        return this.inventoryGetItem.call(this.playerInventory.call(serverPlayer), slot);
    }

    public Object copyItem(Object stack) {
        return this.itemCopy.call(stack);
    }

    public boolean itemsMatch(Object a, Object b) {
        return (boolean) this.itemMatches.call(null, a, b);
    }

    public Object[] equipmentSlots() {
        return this.equipmentSlots;
    }

    public Object itemBySlot(Object living, Object slot) {
        return this.getItemBySlot.call(living, slot);
    }

    public Object equipmentPacket(int entityId, List<Object[]> slotItems) {
        List<Object> pairs = new ArrayList<>();
        for (Object[] si : slotItems) {
            pairs.add(this.pairOf.call(null, si[0], si[1]));
        }
        return Ref.construct(this.setEquipmentCtor, entityId, pairs);
    }

    public Object playerInventoryPacket(int slot, Object stack) {
        return Ref.construct(this.setPlayerInventoryCtor, slot, stack);
    }

    public Object heldSlotPacket(int slot) {
        return Ref.construct(this.setHeldSlotCtor, slot);
    }

    public Object blockPos(int x, int y, int z) {
        return Ref.construct(this.blockPosCtor, x, y, z);
    }

    /** 目前挖掘進度：{x, y, z, 階段}；沒有在挖時階段為 -1。 */
    public int[] destroyProgress(Object serverPlayer) {
        Object gm = this.playerGameMode.get(serverPlayer);
        boolean destroying = (boolean) this.isDestroying.get(gm);
        int stage = (int) this.lastSentState.get(gm);
        Object pos = this.destroyPos.get(gm);
        if (pos == null) {
            return new int[]{0, 0, 0, -1};
        }
        return new int[]{(int) this.blockPosX.call(pos), (int) this.blockPosY.call(pos), (int) this.blockPosZ.call(pos), destroying ? stage : -1};
    }

    public Object blockDestructionPacket(int entityId, int x, int y, int z, int stage) {
        return Ref.construct(this.blockDestructionCtor, entityId, blockPos(x, y, z), stage);
    }

    /** 方塊被破壞的粒子與音效（伺服器不會送給破壞者本人）。 */
    public Object blockBreakEffect(Block block) {
        Object state = this.craftBlockDataState.call(block.getBlockData());
        int stateId = (int) this.blockGetId.call(null, state);
        return Ref.construct(this.levelEventCtor, 2001, blockPos(block.getX(), block.getY(), block.getZ()), stateId, false);
    }

    /** 放置方塊的音效（伺服器不會送給放置者本人）。 */
    public Object blockPlaceSound(Block block, long seed) {
        Object state = this.craftBlockDataState.call(block.getBlockData());
        Object soundType = this.stateSoundType.call(state);
        Object event = this.soundTypePlace.call(soundType);
        Object holderObj = this.wrapSoundHolder.call(this.soundEventRegistry, event);
        float volume = ((float) this.soundTypeVolume.call(soundType) + 1.0f) / 2.0f;
        float pitch = (float) this.soundTypePitch.call(soundType) * 0.8f;
        return Ref.construct(this.soundPacketCtor, holderObj, this.soundSourceBlocks,
            block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5, volume, pitch, seed);
    }

    public Collection<?> activeEffects(Object living) {
        return (Collection<?>) this.effectsOf.call(living);
    }

    public Swing swing() {
        return this.swing;
    }

    // ================================================================ 聊天

    /** 把簽章聊天轉成系統訊息；失敗回傳 null。 */
    public Object convertPlayerChat(Object chatPacket) {
        Object content = this.chatUnsigned.get(chatPacket);
        if (content == null) {
            Object body = this.chatBody.get(chatPacket);
            content = this.componentLiteral.call(null, (String) this.bodyContent.call(body));
        }
        Object decorated = this.chatTypeDecorate.call(this.chatType.get(chatPacket), content);
        return Ref.construct(this.systemChatCtor, decorated, false);
    }

    // ================================================================ 揮手

    /** 揮手動畫：26.3 起用 ClientboundSwingAnimationPacket，之前用 ClientboundAnimatePacket。 */
    public static final class Swing {
        private final boolean modern;
        // 舊版
        private Ref.Getter swinging;
        private Ref.Getter swingTime;
        private Ref.Getter swingingArm;
        private Constructor<?> animateCtor;
        // 26.3
        private Ref.Invoker currentSwing;
        private Ref.Getter swingState;
        private Ref.Getter swingTicks;
        private Ref.Invoker descHand;
        private Ref.Invoker descAnimation;
        private Constructor<?> swingCtor;
        private Object mainHand;

        Swing(PacketClasses packets, Class<?> livingEntity, Class<?> entity) {
            Class<?> hand = Ref.cls("net.minecraft.world.InteractionHand");
            for (Object c : hand.getEnumConstants()) {
                if (((Enum<?>) c).name().equals("MAIN_HAND")) {
                    this.mainHand = c;
                }
            }
            if (packets.swingAnimation != null) {
                this.modern = true;
                this.currentSwing = Ref.invoker(livingEntity, "getCurrentSwing");
                this.swingState = Ref.getter(livingEntity, "swingState");
                Class<?> state = Ref.cls("net.minecraft.world.entity.LivingEntity$SwingState");
                this.swingTicks = Ref.getter(state, "ticks");
                Class<?> desc = Ref.cls("net.minecraft.world.entity.LivingEntity$SwingDescription");
                this.descHand = Ref.invoker(desc, "hand");
                this.descAnimation = Ref.invoker(desc, "animation");
                this.swingCtor = Ref.ctor(packets.swingAnimation, int.class, hand, Ref.cls("net.minecraft.world.item.component.SwingAnimation"));
            } else {
                this.modern = false;
                this.swinging = Ref.getter(livingEntity, "swinging");
                this.swingTime = Ref.getter(livingEntity, "swingTime");
                this.swingingArm = Ref.getter(livingEntity, "swingingArm");
                this.animateCtor = Ref.ctor(Ref.cls("net.minecraft.network.protocol.game.ClientboundAnimatePacket"), entity, int.class);
            }
        }

        /** 讀取揮手狀態：{是否在揮手, 揮手計時}。 */
        public int[] state(Object living) {
            if (this.modern) {
                Object desc = this.currentSwing.call(living);
                Object st = this.swingState.get(living);
                int ticks = st == null ? 0 : (int) this.swingTicks.get(st);
                return new int[]{desc != null ? 1 : 0, ticks};
            }
            return new int[]{(boolean) this.swinging.get(living) ? 1 : 0, (int) this.swingTime.get(living)};
        }

        public Object packet(Object living, int entityId) {
            if (this.modern) {
                Object desc = this.currentSwing.call(living);
                if (desc == null) {
                    return null;
                }
                return Ref.construct(this.swingCtor, entityId, this.descHand.call(desc), this.descAnimation.call(desc));
            }
            Object arm = this.swingingArm.get(living);
            int action = arm == this.mainHand || arm == null ? 0 : 3;
            return Ref.construct(this.animateCtor, living, action);
        }
    }

    /** 不可變清單的小工具。 */
    public static <T> List<T> listOf(Collection<T> c) {
        return ImmutableList.copyOf(c);
    }

    public static <E extends Enum<E>> EnumSet<E> enumSetOf(Class<E> type, Collection<E> values) {
        EnumSet<E> set = EnumSet.noneOf(type);
        set.addAll(values);
        return set;
    }
}
