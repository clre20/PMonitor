package clre20.pMonitor.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把動作寫成 Flashback 回放檔（工作資料夾形式，結束時再壓成 zip）。
 * <p>
 * 只能在錄製器的寫檔執行緒上使用。段落檔邊錄邊寫入磁碟，
 * metadata.json 在每段結束時更新，所以當機時最多只會遺失尚未寫入的最後幾秒，
 * 並由 {@link Recovery} 在下次啟動時修復。
 */
public final class FlashbackWriter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FLUSH_THRESHOLD = 256 * 1024;

    private final Path dir;
    private final ByteBuf buf = Unpooled.buffer(FLUSH_THRESHOLD * 2);
    private final AtomicLong bytesWritten = new AtomicLong();
    /** 第一個快照結束時已寫入的位元組數（-1 = 尚未完成）；分段判斷容量時扣掉這部分。 */
    private volatile long firstSnapshotBytes = -1;

    // metadata
    private final UUID replayId = UUID.randomUUID();
    private final String name;
    private final String versionString;
    private final String worldName;
    private final int dataVersion;
    private final int protocolVersion;
    private final LinkedHashMap<String, Integer> segments = new LinkedHashMap<>();
    private int totalTicks;

    // 目前段落
    private FileChannel segment;
    private String segmentName;
    private long segmentPosition;
    private long snapshotSizePosition = -1;
    private boolean inSnapshot;
    private int segmentTicks;

    // 區塊快取
    private final Map<HashKey, Integer> chunkIndex = new HashMap<>();
    private int chunkCount;
    private final Map<Integer, FileChannel> chunkFiles = new HashMap<>();
    private final MessageDigest digest;

    public FlashbackWriter(Path dir, String name, String versionString, String worldName, int dataVersion, int protocolVersion) throws IOException {
        this.dir = dir;
        this.name = name;
        this.versionString = versionString;
        this.worldName = worldName;
        this.dataVersion = dataVersion;
        this.protocolVersion = protocolVersion;
        Files.createDirectories(dir.resolve("level_chunk_caches"));
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public Path dir() {
        return this.dir;
    }

    /** 扣掉開頭快照後的錄製內容大小；開頭快照尚未寫完時為 0。 */
    public long contentBytes() {
        long first = this.firstSnapshotBytes;
        return first < 0 ? 0 : Math.max(0, this.bytesWritten.get() - first);
    }

    public long bytesWritten() {
        return this.bytesWritten.get();
    }

    public int totalTicks() {
        return this.totalTicks;
    }

    public boolean hasOpenSegment() {
        return this.segment != null;
    }

    // ---------------------------------------------------------------- 段落

    public void beginSegment() throws IOException {
        if (this.segment != null) {
            throw new IllegalStateException("上一段尚未結束");
        }
        this.segmentName = "c" + this.segments.size() + ".flashback";
        this.segment = FileChannel.open(this.dir.resolve(this.segmentName),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.segmentPosition = 0;
        this.segmentTicks = 0;
        FlashbackFormat.writeHeader(this.buf);
    }

    public void beginSnapshot() {
        if (this.inSnapshot) {
            throw new IllegalStateException("快照已開始");
        }
        this.inSnapshot = true;
        this.snapshotSizePosition = this.segmentPosition + this.buf.writerIndex();
        this.buf.writeInt(0xDEADBEEF);
    }

    public void endSnapshot() throws IOException {
        if (!this.inSnapshot) {
            throw new IllegalStateException("快照尚未開始");
        }
        this.inSnapshot = false;
        if (this.firstSnapshotBytes < 0) {
            this.firstSnapshotBytes = this.bytesWritten.get();
        }
        long end = this.segmentPosition + this.buf.writerIndex();
        int size = (int) (end - this.snapshotSizePosition - 4);
        long bufStart = this.segmentPosition;
        if (this.snapshotSizePosition >= bufStart) {
            this.buf.setInt((int) (this.snapshotSizePosition - bufStart), size);
        } else {
            // 快照長度欄位已經寫進檔案，直接覆寫檔案中的位置
            ByteBuffer bb = ByteBuffer.allocate(4).putInt(size);
            bb.flip();
            while (bb.hasRemaining()) {
                this.segment.write(bb, this.snapshotSizePosition + (4 - bb.remaining()));
            }
        }
        this.snapshotSizePosition = -1;
    }

    /** 結束目前段落並更新 metadata.json。 */
    public void endSegment() throws IOException {
        if (this.segment == null) {
            return;
        }
        if (this.inSnapshot) {
            endSnapshot();
        }
        if (this.segmentTicks == 0) {
            // 避免長度為 0 的段落
            nextTick();
        }
        flush();
        this.segment.force(false);
        this.segment.close();
        this.segment = null;
        this.segments.put(this.segmentName, this.segmentTicks);
        writeMetadata();
    }

    // ---------------------------------------------------------------- 動作

    public void nextTick() throws IOException {
        FlashbackFormat.writeVarInt(this.buf, FlashbackFormat.NEXT_TICK);
        this.buf.writeInt(0);
        this.segmentTicks++;
        this.totalTicks++;
        maybeFlush();
    }

    public int segmentTicks() {
        return this.segmentTicks;
    }

    public void action(int id, byte[] payload) throws IOException {
        FlashbackFormat.writeVarInt(this.buf, id);
        this.buf.writeInt(payload.length);
        this.buf.writeBytes(payload);
        maybeFlush();
    }

    public void action(int id, ByteBuf payload) throws IOException {
        FlashbackFormat.writeVarInt(this.buf, id);
        this.buf.writeInt(payload.readableBytes());
        this.buf.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
        maybeFlush();
    }

    public void gamePacket(byte[] encoded) throws IOException {
        action(FlashbackFormat.GAME_PACKET, encoded);
    }

    public void configurationPacket(byte[] encoded) throws IOException {
        action(FlashbackFormat.CONFIGURATION_PACKET, encoded);
    }

    /** 區塊封包：相同內容只存一次，之後用索引參照。 */
    public void chunk(byte[] encoded) throws IOException {
        this.digest.reset();
        HashKey key = new HashKey(this.digest.digest(encoded));
        Integer index = this.chunkIndex.get(key);
        if (index == null) {
            index = this.chunkCount++;
            this.chunkIndex.put(key, index);
            int file = index / FlashbackFormat.CHUNK_CACHE_SIZE;
            FileChannel fc = this.chunkFiles.get(file);
            if (fc == null) {
                // 前一個快取檔已滿，關閉它
                FileChannel prev = this.chunkFiles.remove(file - 1);
                if (prev != null) {
                    prev.close();
                }
                fc = FileChannel.open(this.dir.resolve("level_chunk_caches").resolve(String.valueOf(file)),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                this.chunkFiles.put(file, fc);
            }
            ByteBuffer bb = ByteBuffer.allocate(4 + encoded.length);
            bb.putInt(encoded.length).put(encoded).flip();
            while (bb.hasRemaining()) {
                fc.write(bb);
            }
            this.bytesWritten.addAndGet(4L + encoded.length);
        }
        ByteBuf payload = Unpooled.buffer(5);
        FlashbackFormat.writeVarInt(payload, index);
        action(FlashbackFormat.LEVEL_CHUNK_CACHED, payload);
        payload.release();
    }

    // ---------------------------------------------------------------- 檔案

    private void maybeFlush() throws IOException {
        if (this.buf.writerIndex() >= FLUSH_THRESHOLD) {
            flush();
        }
    }

    public void flush() throws IOException {
        if (this.segment == null || this.buf.writerIndex() == 0) {
            return;
        }
        // 快照長度欄位還在緩衝區內時不能先寫出去（之後要回填）
        if (this.inSnapshot && this.snapshotSizePosition >= this.segmentPosition) {
            int keepFrom = (int) (this.snapshotSizePosition - this.segmentPosition);
            if (keepFrom == 0) {
                return;
            }
            writeOut(keepFrom);
            return;
        }
        writeOut(this.buf.writerIndex());
    }

    private void writeOut(int length) throws IOException {
        ByteBuffer bb = this.buf.nioBuffer(0, length);
        while (bb.hasRemaining()) {
            this.segment.write(bb);
        }
        this.segmentPosition += length;
        this.bytesWritten.addAndGet(length);
        // 保留尚未寫出的部分
        this.buf.readerIndex(length);
        this.buf.discardReadBytes();
    }

    private void writeMetadata() throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("uuid", this.replayId.toString());
        meta.addProperty("name", this.name);
        meta.addProperty("version_string", this.versionString);
        if (this.worldName != null) {
            meta.addProperty("world_name", this.worldName);
        }
        meta.addProperty("data_version", this.dataVersion);
        meta.addProperty("protocol_version", this.protocolVersion);
        meta.addProperty("total_ticks", this.totalTicks);
        JsonObject chunks = new JsonObject();
        for (Map.Entry<String, Integer> e : this.segments.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("duration", e.getValue());
            c.addProperty("forcePlaySnapshot", false);
            chunks.add(e.getKey(), c);
        }
        meta.add("chunks", chunks);
        Path tmp = this.dir.resolve("metadata.json.tmp");
        Files.writeString(tmp, GSON.toJson(meta), StandardCharsets.UTF_8);
        Files.move(tmp, this.dir.resolve("metadata.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 關閉所有檔案（不寫 metadata）。 */
    public void close() {
        try {
            if (this.segment != null) {
                flush();
                this.segment.close();
                this.segment = null;
            }
        } catch (IOException ignored) {
        }
        for (FileChannel fc : this.chunkFiles.values()) {
            try {
                fc.close();
            } catch (IOException ignored) {
            }
        }
        this.chunkFiles.clear();
        this.buf.release();
    }

    private record HashKey(byte[] hash) {
        @Override
        public boolean equals(Object o) {
            return o instanceof HashKey k && Arrays.equals(this.hash, k.hash);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.hash);
        }
    }
}
