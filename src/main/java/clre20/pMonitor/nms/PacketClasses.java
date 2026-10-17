package clre20.pMonitor.nms;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 封包類別與處理方式。
 * <p>
 * Flashback 回放時，只要遇到它不支援的封包就會直接報錯，所以這裡用「白名單」：
 * 只有確定 Flashback 會處理的封包才寫進回放檔，其餘全部略過。
 */
public final class PacketClasses {

    public enum Kind {
        /** 不寫入。 */
        DROP,
        /** 原樣寫入（ActionGamePacket）。 */
        WRITE,
        /** 區塊資料：寫進 level_chunk_caches 並用 ActionLevelChunkCached 參照。 */
        CHUNK,
        /** 玩家聊天：轉成系統訊息（Flashback 不支援簽章聊天）。 */
        PLAYER_CHAT,
        /** 封包組：拆開逐一處理。 */
        BUNDLE
    }

    private static final String GAME = "net.minecraft.network.protocol.game.";
    private static final String COMMON = "net.minecraft.network.protocol.common.";

    /** Flashback 回放支援、而且由伺服器端錄製時應該保留的封包。 */
    private static final Set<String> WRITE_GAME = Set.of(
        "ClientboundAddEntityPacket",
        "ClientboundSetObjectivePacket",
        "ClientboundAnimatePacket",
        "ClientboundHurtAnimationPacket",
        "ClientboundSwingAnimationPacket",
        "ClientboundBlockDestructionPacket",
        "ClientboundBlockEntityDataPacket",
        "ClientboundBlockEventPacket",
        "ClientboundBlockUpdatePacket",
        "ClientboundSystemChatPacket",
        "ClientboundDisguisedChatPacket",
        "ClientboundSectionBlocksUpdatePacket",
        "ClientboundMapItemDataPacket",
        "ClientboundEntityEventPacket",
        "ClientboundSetEntityLinkPacket",
        "ClientboundEntityPositionSyncPacket",
        "ClientboundSetPassengersPacket",
        "ClientboundExplodePacket",
        "ClientboundGameEventPacket",
        "ClientboundChunksBiomesPacket",
        "ClientboundLevelEventPacket",
        "ClientboundLevelParticlesPacket",
        "ClientboundPlayerInfoRemovePacket",
        "ClientboundPlayerInfoUpdatePacket",
        "ClientboundRemoveEntitiesPacket",
        "ClientboundRemoveMobEffectPacket",
        "ClientboundRespawnPacket",
        "ClientboundSetDisplayObjectivePacket",
        "ClientboundSetEntityDataPacket",
        "ClientboundSetEntityMotionPacket",
        "ClientboundSetEquipmentPacket",
        "ClientboundSetExperiencePacket",
        "ClientboundSetHealthPacket",
        "ClientboundSetPlayerTeamPacket",
        "ClientboundSetScorePacket",
        "ClientboundResetScorePacket",
        "ClientboundSetDefaultSpawnPositionPacket",
        "ClientboundSetTimePacket",
        "ClientboundSoundPacket",
        "ClientboundSoundEntityPacket",
        "ClientboundTakeItemEntityPacket",
        "ClientboundTeleportEntityPacket",
        "ClientboundUpdateAttributesPacket",
        "ClientboundUpdateMobEffectPacket",
        "ClientboundChangeDifficultyPacket",
        "ClientboundInitializeBorderPacket",
        "ClientboundSetBorderLerpSizePacket",
        "ClientboundSetBorderSizePacket",
        "ClientboundSetBorderWarningDelayPacket",
        "ClientboundSetBorderWarningDistancePacket",
        "ClientboundSetBorderCenterPacket",
        "ClientboundTabListPacket",
        "ClientboundBossEventPacket",
        "ClientboundStopSoundPacket",
        "ClientboundLightUpdatePacket",
        "ClientboundClearTitlesPacket",
        "ClientboundServerDataPacket",
        "ClientboundSetTitleTextPacket",
        "ClientboundSetSubtitleTextPacket",
        "ClientboundSetTitlesAnimationPacket",
        "ClientboundSetActionBarTextPacket",
        "ClientboundDamageEventPacket",
        "ClientboundProjectilePowerPacket"
    );

    private static final Set<String> WRITE_COMMON = Set.of(
        "ClientboundResourcePackPushPacket",
        "ClientboundResourcePackPopPacket"
    );

    private final Map<Class<?>, Kind> kinds = new HashMap<>();

    public final Class<?> packet;
    public final Class<?> bundle;
    public final Class<?> login;
    public final Class<?> respawn;
    public final Class<?> startConfiguration;
    public final Class<?> addEntity;
    public final Class<?> removeEntities;
    public final Class<?> chunkWithLight;
    public final Class<?> forgetChunk;
    public final Class<?> setEntityData;
    public final Class<?> setEquipment;
    public final Class<?> setPassengers;
    public final Class<?> teleportEntity;
    public final Class<?> positionSync;
    public final Class<?> moveEntity;
    public final Class<?> rotateHead;
    public final Class<?> bossEvent;
    public final Class<?> tabList;
    public final Class<?> playerInfoUpdate;
    public final Class<?> playerInfoRemove;
    public final Class<?> setPlayerTeam;
    public final Class<?> setObjective;
    public final Class<?> setDisplayObjective;
    public final Class<?> setScore;
    public final Class<?> resetScore;
    public final Class<?> setTime;
    public final Class<?> initializeBorder;
    public final Class<?> changeDifficulty;
    public final Class<?> setDefaultSpawn;
    public final Class<?> serverData;
    public final Class<?> gameEvent;
    public final Class<?> resourcePackPush;
    public final Class<?> resourcePackPop;
    public final Class<?> setHealth;
    public final Class<?> setExperience;
    public final Class<?> updateMobEffect;
    public final Class<?> removeMobEffect;
    public final Class<?> mapItemData;
    public final Class<?> playerChat;
    public final Class<?> systemChat;
    /** 26.3 起才有。 */
    public final Class<?> swingAnimation;
    public final Set<Class<?>> borderOps;

    public PacketClasses() {
        this.packet = Ref.cls("net.minecraft.network.protocol.Packet");
        this.bundle = Ref.cls(GAME + "ClientboundBundlePacket");
        this.login = Ref.cls(GAME + "ClientboundLoginPacket");
        this.respawn = Ref.cls(GAME + "ClientboundRespawnPacket");
        this.startConfiguration = Ref.cls(GAME + "ClientboundStartConfigurationPacket");
        this.addEntity = Ref.cls(GAME + "ClientboundAddEntityPacket");
        this.removeEntities = Ref.cls(GAME + "ClientboundRemoveEntitiesPacket");
        this.chunkWithLight = Ref.cls(GAME + "ClientboundLevelChunkWithLightPacket");
        this.forgetChunk = Ref.cls(GAME + "ClientboundForgetLevelChunkPacket");
        this.setEntityData = Ref.cls(GAME + "ClientboundSetEntityDataPacket");
        this.setEquipment = Ref.cls(GAME + "ClientboundSetEquipmentPacket");
        this.setPassengers = Ref.cls(GAME + "ClientboundSetPassengersPacket");
        this.teleportEntity = Ref.cls(GAME + "ClientboundTeleportEntityPacket");
        this.positionSync = Ref.cls(GAME + "ClientboundEntityPositionSyncPacket");
        this.moveEntity = Ref.cls(GAME + "ClientboundMoveEntityPacket");
        this.rotateHead = Ref.cls(GAME + "ClientboundRotateHeadPacket");
        this.bossEvent = Ref.cls(GAME + "ClientboundBossEventPacket");
        this.tabList = Ref.cls(GAME + "ClientboundTabListPacket");
        this.playerInfoUpdate = Ref.cls(GAME + "ClientboundPlayerInfoUpdatePacket");
        this.playerInfoRemove = Ref.cls(GAME + "ClientboundPlayerInfoRemovePacket");
        this.setPlayerTeam = Ref.cls(GAME + "ClientboundSetPlayerTeamPacket");
        this.setObjective = Ref.cls(GAME + "ClientboundSetObjectivePacket");
        this.setDisplayObjective = Ref.cls(GAME + "ClientboundSetDisplayObjectivePacket");
        this.setScore = Ref.cls(GAME + "ClientboundSetScorePacket");
        this.resetScore = Ref.cls(GAME + "ClientboundResetScorePacket");
        this.setTime = Ref.cls(GAME + "ClientboundSetTimePacket");
        this.initializeBorder = Ref.cls(GAME + "ClientboundInitializeBorderPacket");
        this.changeDifficulty = Ref.cls(GAME + "ClientboundChangeDifficultyPacket");
        this.setDefaultSpawn = Ref.cls(GAME + "ClientboundSetDefaultSpawnPositionPacket");
        this.serverData = Ref.cls(GAME + "ClientboundServerDataPacket");
        this.gameEvent = Ref.cls(GAME + "ClientboundGameEventPacket");
        this.resourcePackPush = Ref.cls(COMMON + "ClientboundResourcePackPushPacket");
        this.resourcePackPop = Ref.cls(COMMON + "ClientboundResourcePackPopPacket");
        this.setHealth = Ref.cls(GAME + "ClientboundSetHealthPacket");
        this.setExperience = Ref.cls(GAME + "ClientboundSetExperiencePacket");
        this.updateMobEffect = Ref.cls(GAME + "ClientboundUpdateMobEffectPacket");
        this.removeMobEffect = Ref.cls(GAME + "ClientboundRemoveMobEffectPacket");
        this.mapItemData = Ref.cls(GAME + "ClientboundMapItemDataPacket");
        this.playerChat = Ref.cls(GAME + "ClientboundPlayerChatPacket");
        this.systemChat = Ref.cls(GAME + "ClientboundSystemChatPacket");
        this.swingAnimation = Ref.clsOrNull(GAME + "ClientboundSwingAnimationPacket");
        this.borderOps = Set.of(
            Ref.cls(GAME + "ClientboundSetBorderLerpSizePacket"),
            Ref.cls(GAME + "ClientboundSetBorderSizePacket"),
            Ref.cls(GAME + "ClientboundSetBorderWarningDelayPacket"),
            Ref.cls(GAME + "ClientboundSetBorderWarningDistancePacket"),
            Ref.cls(GAME + "ClientboundSetBorderCenterPacket")
        );

        for (String name : WRITE_GAME) {
            Class<?> c = Ref.clsOrNull(GAME + name);
            if (c != null) {
                this.kinds.put(c, Kind.WRITE);
            }
        }
        for (String name : WRITE_COMMON) {
            Class<?> c = Ref.clsOrNull(COMMON + name);
            if (c != null) {
                this.kinds.put(c, Kind.WRITE);
            }
        }
        this.kinds.put(this.chunkWithLight, Kind.CHUNK);
        this.kinds.put(this.playerChat, Kind.PLAYER_CHAT);
        this.kinds.put(this.bundle, Kind.BUNDLE);
        // 這些封包另外處理（重新進入遊戲時會重新建立快照）
        this.kinds.put(this.login, Kind.DROP);
    }

    public Kind kind(Class<?> packetClass) {
        Kind kind = this.kinds.get(packetClass);
        if (kind != null) {
            return kind;
        }
        // ClientboundMoveEntityPacket 的子類別等：一律略過
        return Kind.DROP;
    }

    public boolean isMoveEntity(Class<?> packetClass) {
        return this.moveEntity.isAssignableFrom(packetClass);
    }
}
