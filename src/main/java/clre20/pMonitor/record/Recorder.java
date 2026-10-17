package clre20.pMonitor.record;

import clre20.pMonitor.net.ClientState;
import clre20.pMonitor.nms.Nms;
import clre20.pMonitor.nms.PacketClasses;
import clre20.pMonitor.nms.Ref;
import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 錄製一位玩家的一個檔案（一個分段）。
 * <ul>
 *   <li>Netty 執行緒：{@link #capture} 把送往玩家的封包編碼後放進佇列</li>
 *   <li>主執行緒：{@link #tick} 每 tick 排空佇列、補上玩家自己的動作與實體位置、寫入 NextTick</li>
 *   <li>寫檔執行緒：所有檔案寫入都在這裡依序執行</li>
 * </ul>
 */
public final class Recorder {

    public enum StopReason { MANUAL, QUIT, SHUTDOWN, SPLIT, STORAGE, ERROR }

    /** 快照超過這個毫秒數就記 log（除錯用：-Dpmonitor.snapshotLogMs=0 會記錄每一次）。 */
    private static final long SLOW_SNAPSHOT_MS = Long.getLong("pmonitor.snapshotLogMs", 100L);
    /** 快照的區塊分散到多個 tick 產生，每 tick 最多用這麼多時間（毫秒）。 */
    private static final long CHUNK_BUDGET_NANOS = Long.getLong("pmonitor.chunkBudgetMs", 3L) * 1_000_000L;
    /** 每 tick 至少產生這麼多個區塊，避免拖太久。 */
    private static final int CHUNK_MIN_PER_TICK = 16;
    /** 超過這麼多 tick 還沒產生完就一次做完（保險）。 */
    private static final int CHUNK_MAX_TICKS = 100;
    private static final byte KIND_GAME = 0;
    private static final byte KIND_CHUNK = 1;

    private record Captured(byte kind, byte[] data) {
    }

    private final Logger logger;
    private final Nms nms;
    private final UUID uuid;
    private final String playerName;
    private final ClientState state;
    private final RecordingFile file;
    private final FlashbackWriter writer;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Captured> queue = new ConcurrentLinkedQueue<>();
    private final List<Object> synthesized = new ArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final long startMillis = System.currentTimeMillis();

    private volatile boolean accepting;
    private volatile boolean suspended;
    private volatile boolean resetLocal;
    private boolean started;
    private boolean stopped;
    private int ticks;
    private int segmentTicks;

    // 玩家本身的上一次狀態
    private final Object[] lastEquipment;
    private final Object[] lastHotbar = new Object[9];
    private int lastHeldSlot = -1;
    private int[] lastDestroy = {0, 0, 0, -1};
    private boolean wasSwinging;
    private int lastSwingTime;
    /** 正在分散產生區塊的快照；不為 null 時，寫入工作先暫存在 deferred，快照寫完後再依序送出。 */
    private PendingSnapshot pendingSnapshot;
    private final List<IoTask> deferred = new ArrayList<>();

    private static final class PendingSnapshot {
        final SnapshotBuilder.Result snapshot;
        final List<SnapshotBuilder.Item> chunks = new ArrayList<>();
        final long t0;
        int next;
        int ticks;
        long buildNanos;
        /** 單一 tick 內最長的花費（第一個 tick 含快照其他內容）。 */
        long maxStepNanos;

        PendingSnapshot(SnapshotBuilder.Result snapshot, long t0) {
            this.snapshot = snapshot;
            this.t0 = t0;
        }
    }

    private final Map<Integer, double[]> lastPositions = new HashMap<>();
    private long positionStamp;

    public Recorder(Logger logger, Player player, ClientState state, RecordingFile file) throws IOException {
        this.logger = logger;
        this.nms = Nms.get();
        this.uuid = player.getUniqueId();
        this.playerName = player.getName();
        this.state = state;
        this.file = file;
        this.writer = new FlashbackWriter(file.workDir(), file.displayName(), file.mcVersion(), file.worldName(),
            file.dataVersion(), file.protocolVersion());
        this.lastEquipment = new Object[this.nms.equipmentSlots().length];
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PMonitor-Writer-" + this.playerName);
            t.setDaemon(false);
            return t;
        });
        file.writeInfo(RecordingFile.Status.RECORDING, 0, this.startMillis, this.startMillis);
    }

    // ================================================================ 開始

    /** 在主執行緒開始錄製（建立快照並掛上封包擷取）。玩家必須已經進入遊戲。 */
    public void start(Player player) {
        long t0 = System.nanoTime();
        SnapshotBuilder.Result snapshot = SnapshotBuilder.build(this.nms, player, this.state, () -> {
            this.accepting = true;
            this.state.attach(this);
        }, true);
        this.lastPositions.putAll(snapshot.positions());
        initLocal(player);
        submit(this.writer::beginSegment);
        submit(this.writer::beginSnapshot);
        this.started = true;
        beginSnapshotBody(snapshot, t0);
    }

    /**
     * 快照的其他內容已備妥，接著產生區塊。區塊很多時分散到之後幾個 tick，
     * 這段期間的封包與動作先暫存，快照寫完後再依序寫入（方塊變化是「設成某方塊」，重複套用不影響結果）。
     */
    private void beginSnapshotBody(SnapshotBuilder.Result snapshot, long t0) {
        PendingSnapshot ps = new PendingSnapshot(snapshot, t0);
        ps.buildNanos = System.nanoTime() - t0;
        this.pendingSnapshot = ps;
        advanceSnapshot(false);
    }

    /**
     * 產生下一批區塊；全部產生完就寫入快照並送出暫存的工作。
     *
     * @param all true 時一次產生剩下的全部（停止錄製、暫停時）
     */
    private void advanceSnapshot(boolean all) {
        PendingSnapshot ps = this.pendingSnapshot;
        if (ps == null) {
            return;
        }
        long[] order = ps.snapshot.chunkOrder();
        long start = System.nanoTime();
        int done = 0;
        boolean force = all || ps.ticks >= CHUNK_MAX_TICKS;
        boolean canBuild = Bukkit.isPrimaryThread();
        while (ps.next < order.length && canBuild) {
            if (!force && done >= CHUNK_MIN_PER_TICK && System.nanoTime() - start >= CHUNK_BUDGET_NANOS) {
                break;
            }
            try {
                SnapshotBuilder.Item item = SnapshotBuilder.chunkItem(this.nms, ps.snapshot.level(), order[ps.next]);
                if (item != null) {
                    ps.chunks.add(item);
                }
            } catch (Throwable t) {
                this.logger.log(Level.FINE, "產生快照區塊失敗", t);
            }
            ps.next++;
            done++;
        }
        long step = System.nanoTime() - start;
        ps.buildNanos += step;
        if (ps.ticks == 0) {
            step += System.nanoTime() - ps.t0 - step;
        }
        ps.maxStepNanos = Math.max(ps.maxStepNanos, step);
        ps.ticks++;
        if (ps.next < order.length && canBuild) {
            return;
        }
        // 全部產生完（或不在主執行緒而無法繼續）：寫入快照，再送出暫存的工作
        this.pendingSnapshot = null;
        List<SnapshotBuilder.Item> items = new ArrayList<>(ps.snapshot.items());
        items.addAll(ps.snapshot.chunkIndex(), ps.chunks);
        writeSnapshotItems(items);
        submit(this.writer::endSnapshot);
        List<IoTask> later = new ArrayList<>(this.deferred);
        this.deferred.clear();
        // 計分板、王者血條等在快照後再送一次（Flashback 載入第一個快照時會漏掉）
        List<Object> replay = new ArrayList<>(ps.snapshot.replayAfter());
        if (!replay.isEmpty()) {
            Object codec = this.state.playCodec();
            submit(() -> {
                for (Object pk : replay) {
                    this.writer.gamePacket(this.nms.encode(codec, pk));
                }
            });
        }
        for (IoTask t : later) {
            submit(t);
        }
        long ms = ps.buildNanos / 1_000_000;
        if (ms >= SLOW_SNAPSHOT_MS) {
            this.logger.info("建立 " + this.playerName + " 的快照共花了 " + ms + " ms，分散在 " + ps.ticks + " 個 tick，單一 tick 最多 "
                + (ps.maxStepNanos / 1_000_000) + " ms（" + items.size() + " 個項目，其中區塊 " + ps.chunks.size() + " 個）");
        }
    }


    private void writeSnapshotItems(List<SnapshotBuilder.Item> items) {
        Object codec = this.state.playCodec();
        Object configCodec = this.nms.configCodec();
        // 分批送出，避免單一任務太大
        List<SnapshotBuilder.Item> batch = new ArrayList<>(items);
        submit(() -> {
            for (SnapshotBuilder.Item item : batch) {
                switch (item.type()) {
                    case CONFIG -> this.writer.configurationPacket(this.nms.encode(configCodec, item.packet()));
                    case GAME -> this.writer.gamePacket(this.nms.encode(codec, item.packet()));
                    case CHUNK -> this.writer.chunk(this.nms.encode(codec, item.packet()));
                    case RAW -> this.writer.action(item.action(), item.raw());
                }
            }
        });
    }

    // ================================================================ 擷取（Netty 執行緒，持有 ClientState 鎖）

    public void capture(Object packet, Object codec) {
        if (!this.accepting || this.suspended || codec == null) {
            return;
        }
        PacketClasses p = this.nms.packets;
        Class<?> c = packet.getClass();
        try {
            switch (p.kind(c)) {
                case WRITE -> {
                    if (c == p.respawn) {
                        this.resetLocal = true;
                    }
                    this.queue.add(new Captured(KIND_GAME, this.nms.encode(codec, packet)));
                }
                case CHUNK -> this.queue.add(new Captured(KIND_CHUNK, this.nms.encode(codec, packet)));
                case PLAYER_CHAT -> {
                    Object converted = this.nms.convertPlayerChat(packet);
                    if (converted != null) {
                        this.queue.add(new Captured(KIND_GAME, this.nms.encode(codec, converted)));
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            // 單一封包編碼失敗不影響錄製
            this.logger.log(Level.FINE, "無法記錄封包 " + c.getSimpleName(), t);
        }
    }

    // ================================================================ 每 tick（主執行緒）

    /** @return false 表示錄製已因錯誤中止。 */
    public boolean tick(Player player) {
        if (!this.started || this.stopped) {
            return true;
        }
        if (this.error.get() != null) {
            return false;
        }
        // 玩家重新進入設定階段（例如伺服器要求重新設定）：暫停，回到遊戲後重建快照
        if (!this.state.isPlaying()) {
            if (!this.suspended) {
                advanceSnapshot(true);
                this.suspended = true;
                this.queue.clear();
            }
            return true;
        }
        if (this.suspended) {
            rollover(player);
            this.suspended = false;
            return true;
        }

        advanceSnapshot(false);
        drain();
        try {
            writeLocal(player);
            writePositions(player);
        } catch (Throwable t) {
            this.logger.log(Level.FINE, "記錄玩家動作時發生錯誤", t);
        }
        flushSynthesized();
        submit(this.writer::nextTick);
        this.ticks++;
        this.segmentTicks++;
        if (this.ticks % 20 == 0) {
            submit(this.writer::flush);
        }
        if (this.segmentTicks >= FlashbackFormat.TICKS_PER_SEGMENT) {
            rollover(player);
        }
        return true;
    }

    /** 結束目前段落並以新快照開始下一段。 */
    private void rollover(Player player) {
        long t0 = System.nanoTime();
        advanceSnapshot(true);
        SnapshotBuilder.Result snapshot = SnapshotBuilder.build(this.nms, player, this.state, () -> {
            drain();
            submit(this.writer::endSegment);
            submit(this.writer::beginSegment);
            submit(this.writer::beginSnapshot);
        }, true);
        this.lastPositions.clear();
        this.lastPositions.putAll(snapshot.positions());
        initLocal(player);
        this.segmentTicks = 0;
        beginSnapshotBody(snapshot, t0);
        long now = System.currentTimeMillis();
        submit(() -> this.file.writeInfo(RecordingFile.Status.RECORDING, this.writer.totalTicks(), this.startMillis, now));
    }

    private void drain() {
        List<Captured> batch = new ArrayList<>();
        Captured c;
        while ((c = this.queue.poll()) != null) {
            batch.add(c);
        }
        if (batch.isEmpty()) {
            return;
        }
        submit(() -> {
            for (Captured cap : batch) {
                if (cap.kind == KIND_CHUNK) {
                    this.writer.chunk(cap.data);
                } else {
                    this.writer.gamePacket(cap.data);
                }
            }
        });
    }

    private void flushSynthesized() {
        if (this.synthesized.isEmpty()) {
            return;
        }
        List<Object> batch = new ArrayList<>(this.synthesized);
        this.synthesized.clear();
        Object codec = this.state.playCodec();
        submit(() -> {
            for (Object pk : batch) {
                if (pk instanceof byte[] raw) {
                    this.writer.action(FlashbackFormat.MOVE_ENTITIES, raw);
                } else {
                    this.writer.gamePacket(this.nms.encode(codec, pk));
                }
            }
        });
    }

    // ================================================================ 玩家本身

    private void initLocal(Player player) {
        Object sp = this.nms.handle(player);
        Object[] slots = this.nms.equipmentSlots();
        for (int i = 0; i < slots.length; i++) {
            this.lastEquipment[i] = this.nms.copyItem(this.nms.itemBySlot(sp, slots[i]));
        }
        for (int i = 0; i < 9; i++) {
            this.lastHotbar[i] = this.nms.copyItem(this.nms.hotbarItem(sp, i));
        }
        this.lastHeldSlot = player.getInventory().getHeldItemSlot();
        this.lastDestroy = this.nms.destroyProgress(sp);
        int[] swing = this.nms.swing().state(sp);
        this.wasSwinging = swing[0] != 0;
        this.lastSwingTime = swing[1];
        this.resetLocal = false;
    }

    private void writeLocal(Player player) {
        Object sp = this.nms.handle(player);
        int selfId = this.nms.entityId(sp);
        if (this.resetLocal) {
            // 重生後 Flashback 會重建玩家實體，裝備與快捷欄要重送
            this.resetLocal = false;
            java.util.Arrays.fill(this.lastEquipment, null);
            java.util.Arrays.fill(this.lastHotbar, null);
            this.lastHeldSlot = -1;
        }

        // 裝備（伺服器不會把自己的裝備變化送給自己）
        Object[] slots = this.nms.equipmentSlots();
        List<Object[]> changed = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            Object item = this.nms.itemBySlot(sp, slots[i]);
            if (this.lastEquipment[i] == null || !this.nms.itemsMatch(this.lastEquipment[i], item)) {
                Object copy = this.nms.copyItem(item);
                this.lastEquipment[i] = copy;
                changed.add(new Object[]{slots[i], copy});
            }
        }
        if (!changed.isEmpty()) {
            this.synthesized.add(this.nms.equipmentPacket(selfId, changed));
        }

        // 快捷欄與手持欄位（回放時的第一人稱畫面）
        for (int i = 0; i < 9; i++) {
            Object item = this.nms.hotbarItem(sp, i);
            if (this.lastHotbar[i] == null || !this.nms.itemsMatch(this.lastHotbar[i], item)) {
                Object copy = this.nms.copyItem(item);
                this.lastHotbar[i] = copy;
                this.synthesized.add(this.nms.playerInventoryPacket(i, copy));
            }
        }
        int held = player.getInventory().getHeldItemSlot();
        if (held != this.lastHeldSlot) {
            this.lastHeldSlot = held;
            this.synthesized.add(this.nms.heldSlotPacket(held));
        }

        // 挖掘進度（伺服器只送給其他玩家）
        int[] destroy = this.nms.destroyProgress(sp);
        boolean destroyChanged = destroy[3] != this.lastDestroy[3]
            || (destroy[3] >= 0 && (destroy[0] != this.lastDestroy[0] || destroy[1] != this.lastDestroy[1] || destroy[2] != this.lastDestroy[2]));
        if (destroyChanged) {
            int[] pos = destroy[3] >= 0 ? destroy : this.lastDestroy;
            this.synthesized.add(this.nms.blockDestructionPacket(selfId, pos[0], pos[1], pos[2], destroy[3]));
            this.lastDestroy = destroy;
        }

        // 揮手（伺服器只送給其他玩家）
        int[] swing = this.nms.swing().state(sp);
        boolean swinging = swing[0] != 0;
        if (swinging && (!this.wasSwinging || this.lastSwingTime > swing[1])) {
            Object pk = this.nms.swing().packet(sp, selfId);
            if (pk != null) {
                this.synthesized.add(pk);
            }
        }
        this.wasSwinging = swinging;
        this.lastSwingTime = swing[1];
    }

    /** 方塊被這位玩家破壞（主執行緒，由事件呼叫）。 */
    public void onBlockBreak(Block block) {
        if (!this.started || this.stopped || this.suspended) {
            return;
        }
        try {
            this.synthesized.add(this.nms.blockBreakEffect(block));
        } catch (Throwable t) {
            this.logger.log(Level.FINE, "無法記錄破壞方塊效果", t);
        }
    }

    /** 這位玩家放置了方塊（主執行緒，由事件呼叫）。 */
    public void onBlockPlace(Block block) {
        if (!this.started || this.stopped || this.suspended) {
            return;
        }
        try {
            this.synthesized.add(this.nms.blockPlaceSound(block, ThreadLocalRandom.current().nextLong()));
        } catch (Throwable t) {
            this.logger.log(Level.FINE, "無法記錄放置方塊音效", t);
        }
    }

    // ================================================================ 實體位置

    /**
     * 每 tick 寫入有變動的實體位置。伺服器上存在的實體直接讀伺服器的精確位置，
     * 只存在於封包中的實體（全息圖、NPC）用封包推算的位置。
     */
    private void writePositions(Player player) {
        Object sp = this.nms.handle(player);
        Object level = this.nms.level(sp);
        int selfId = this.nms.entityId(sp);
        String dimension = this.nms.dimensionId(level);
        List<double[]> changed = new ArrayList<>();

        Map<Integer, double[]> fakeById = null;
        int[] ids;
        synchronized (this.state) {
            // 在同一把鎖內先把已擷取的封包排進檔案，再讀實體列表：
            // 網路執行緒是在這把鎖內更新狀態並擷取封包的，這樣列表裡的實體一定已經寫過新增封包
            drain();
            if (!dimension.equals(this.state.dimension())) {
                // 玩家剛換維度，換維度的封包還沒送出：等客戶端也換過去再記錄位置
                return;
            }
            ids = this.state.entityIds();
            for (int id : ids) {
                ClientState.EntityState e = this.state.entity(id);
                if (e != null && e.moved) {
                    e.moved = false;
                    if (fakeById == null) {
                        fakeById = new HashMap<>();
                    }
                    fakeById.put(id, new double[]{e.pos[0], e.pos[1], e.pos[2], e.pos[3], e.pos[4], e.pos[5], e.onGround ? 1 : 0});
                }
            }
        }
        long stamp = ++this.positionStamp;
        checkPosition(changed, stamp, selfId, this.nms.x(sp), this.nms.y(sp), this.nms.z(sp),
            this.nms.yRot(sp), this.nms.xRot(sp), this.nms.headRot(sp), this.nms.onGround(sp));
        for (int id : ids) {
            if (id == selfId) {
                continue;
            }
            Object e = this.nms.entityById(level, id);
            if (e != null) {
                checkPosition(changed, stamp, id, this.nms.x(e), this.nms.y(e), this.nms.z(e),
                    this.nms.yRot(e), this.nms.xRot(e), this.nms.headRot(e), this.nms.onGround(e));
            } else {
                double[] f = fakeById == null ? null : fakeById.remove(id);
                if (f != null) {
                    checkPosition(changed, stamp, id, f[0], f[1], f[2], (float) f[3], (float) f[4], (float) f[5], f[6] != 0);
                } else {
                    touch(id, stamp);
                }
            }
        }
        // 移除已經不在客戶端上的實體
        this.lastPositions.values().removeIf(last -> last[7] != stamp);
        if (!changed.isEmpty()) {
            this.synthesized.add(SnapshotBuilder.moveEntities(dimension, changed));
        }
    }

    /** 實體仍存在但這 tick 沒有新位置（封包實體沒移動）：只更新存在標記。 */
    private void touch(int id, long stamp) {
        double[] last = this.lastPositions.get(id);
        if (last != null) {
            last[7] = stamp;
        }
    }

    /** 位置或角度有變才加入 changed；lastPositions 的值為 [x, y, z, yRot, xRot, headRot, onGround, 存在標記]。 */
    private void checkPosition(List<double[]> changed, long stamp, int id, double x, double y, double z,
                               float yRot, float xRot, float headRot, boolean onGround) {
        double og = onGround ? 1 : 0;
        double[] last = this.lastPositions.get(id);
        if (last != null) {
            last[7] = stamp;
            if (last[0] == x && last[1] == y && last[2] == z && last[3] == yRot && last[4] == xRot
                && last[5] == headRot && last[6] == og) {
                return;
            }
        } else {
            last = new double[8];
            last[7] = stamp;
            this.lastPositions.put(id, last);
        }
        last[0] = x;
        last[1] = y;
        last[2] = z;
        last[3] = yRot;
        last[4] = xRot;
        last[5] = headRot;
        last[6] = og;
        changed.add(new double[]{id, x, y, z, yRot, xRot, headRot, og});
    }

    // ================================================================ 結束

    /** 停止錄製並在背景完成存檔；回傳的 future 完成時檔案已壓縮好。 */
    public CompletableFuture<RecordingFile> stop(StopReason reason) {
        if (this.stopped) {
            return CompletableFuture.completedFuture(this.file);
        }
        advanceSnapshot(true);
        this.stopped = true;
        this.accepting = false;
        this.state.detach(this);
        if (this.started && !this.suspended) {
            drain();
        }
        long end = System.currentTimeMillis();
        CompletableFuture<RecordingFile> future = new CompletableFuture<>();
        boolean failed = reason == StopReason.ERROR || this.error.get() != null;
        try {
            this.executor.execute(() -> {
                try {
                    if (this.writer.hasOpenSegment()) {
                        this.writer.endSegment();
                    }
                    this.writer.close();
                    RecordingFile.Status status = failed ? RecordingFile.Status.INTERRUPTED : RecordingFile.Status.COMPLETED;
                    this.file.writeInfo(status, this.writer.totalTicks(), this.startMillis, end);
                    this.file.finish();
                    future.complete(this.file);
                } catch (Throwable t) {
                    this.logger.log(Level.SEVERE, "儲存錄製檔失敗：" + this.file.workDir(), t);
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        this.executor.shutdown();
        return future;
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private void submit(IoTask task) {
        if (this.pendingSnapshot != null) {
            this.deferred.add(task);
            return;
        }
        if (this.executor.isShutdown()) {
            return;
        }
        this.executor.execute(() -> {
            if (this.error.get() != null) {
                return;
            }
            try {
                task.run();
            } catch (Throwable t) {
                if (this.error.compareAndSet(null, t)) {
                    this.logger.log(Level.SEVERE, "寫入錄製檔時發生錯誤（" + this.playerName + "）：" + Ref.rootMessage(t), t);
                }
            }
        });
    }

    // ================================================================ 狀態

    public UUID uuid() {
        return this.uuid;
    }

    public String playerName() {
        return this.playerName;
    }

    public RecordingFile file() {
        return this.file;
    }

    public long startMillis() {
        return this.startMillis;
    }

    public int ticks() {
        return this.ticks;
    }

    public long bytesWritten() {
        return this.writer.bytesWritten();
    }

    /** 扣掉開頭快照後的錄製內容大小（用來判斷是否要依容量分段）。 */
    public long contentBytes() {
        return this.writer.contentBytes();
    }

    public Throwable error() {
        return this.error.get();
    }

    public boolean isStarted() {
        return this.started;
    }

    public Path workDir() {
        return this.file.workDir();
    }
}
