package clre20.pMonitor.record;

import clre20.pMonitor.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 修復上次沒有正常結束的錄製（伺服器當機、強制終止、斷電）。
 * 已完成的段落直接保留；寫到一半的最後一段會截斷到最後一個完整的 tick。
 */
public final class Recovery {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Recovery() {
    }

    /** 找出所有未完成的工作資料夾。 */
    public static List<Path> findUnfinished(Path recordingsDir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(recordingsDir)) {
            return out;
        }
        try (DirectoryStream<Path> players = Files.newDirectoryStream(recordingsDir)) {
            for (Path playerDir : players) {
                if (!Files.isDirectory(playerDir)) {
                    continue;
                }
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(playerDir)) {
                    for (Path p : entries) {
                        String name = p.getFileName().toString();
                        if (Files.isDirectory(p) && name.startsWith(".") && name.endsWith(RecordingFile.WORK_SUFFIX)) {
                            out.add(p);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** 修復一個工作資料夾；成功回傳 zip 路徑，沒有可用內容回傳 null。 */
    public static Path recover(Path workDir, Logger logger) {
        try {
            Path infoPath = workDir.resolve(RecordingFile.INFO_ENTRY);
            if (!Files.exists(infoPath)) {
                Log.warn("recover-no-info", "file", workDir.getFileName());
                return null;
            }
            JsonObject info = JsonParser.parseString(Files.readString(infoPath, StandardCharsets.UTF_8)).getAsJsonObject();
            RecordingFile file = RecordingFile.fromWorkDir(workDir, info);

            JsonObject meta = null;
            Path metaPath = workDir.resolve("metadata.json");
            if (Files.exists(metaPath)) {
                meta = JsonParser.parseString(Files.readString(metaPath, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            JsonObject chunks = meta != null && meta.has("chunks") ? meta.getAsJsonObject("chunks") : new JsonObject();
            int total = 0;
            for (String key : chunks.keySet()) {
                total += chunks.getAsJsonObject(key).get("duration").getAsInt();
            }

            // 找出寫到一半的段落
            int next = chunks.size();
            Path partial = workDir.resolve("c" + next + ".flashback");
            if (Files.exists(partial)) {
                int ticks = truncateToLastTick(partial);
                if (ticks > 0) {
                    JsonObject c = new JsonObject();
                    c.addProperty("duration", ticks);
                    c.addProperty("forcePlaySnapshot", false);
                    chunks.add(partial.getFileName().toString(), c);
                    total += ticks;
                } else {
                    Files.deleteIfExists(partial);
                }
            }
            // 清掉更後面不該存在的段落
            for (int i = next + 1; Files.exists(workDir.resolve("c" + i + ".flashback")); i++) {
                Files.deleteIfExists(workDir.resolve("c" + i + ".flashback"));
            }

            if (chunks.size() == 0 || total == 0) {
                Log.warn("recover-empty", "file", workDir.getFileName());
                RecordingFile.deleteRecursively(workDir);
                return null;
            }

            if (meta == null) {
                meta = new JsonObject();
                meta.addProperty("uuid", java.util.UUID.randomUUID().toString());
                meta.addProperty("name", file.displayName());
                meta.addProperty("version_string", file.mcVersion());
                meta.addProperty("data_version", file.dataVersion());
                meta.addProperty("protocol_version", file.protocolVersion());
            }
            meta.addProperty("total_ticks", total);
            meta.add("chunks", chunks);
            Files.writeString(metaPath, GSON.toJson(meta), StandardCharsets.UTF_8);

            long start = info.has("start") ? info.get("start").getAsLong() : file.createdMillis();
            long end = start + total * 50L;
            file.writeInfo(RecordingFile.Status.INTERRUPTED, total, start, end);
            file.finish();
            return file.zipFile();
        } catch (Throwable t) {
            Log.severe("recover-failed", t, "file", workDir.getFileName());
            return null;
        }
    }

    /** 解析段落檔，截斷到最後一個完整 tick；回傳 tick 數（快照不完整回傳 0）。 */
    static int truncateToLastTick(Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            if (buf.readableBytes() < 8 || buf.readInt() != FlashbackFormat.MAGIC) {
                return 0;
            }
            int actions = FlashbackFormat.readVarInt(buf);
            for (int i = 0; i < actions; i++) {
                FlashbackFormat.readString(buf);
            }
            if (buf.readableBytes() < 4) {
                return 0;
            }
            int snapshotSize = buf.readInt();
            if (snapshotSize < 0 || snapshotSize > buf.readableBytes()) {
                return 0;
            }
            buf.skipBytes(snapshotSize);
            int ticks = 0;
            int lastGood = buf.readerIndex();
            while (buf.readableBytes() > 0) {
                int id;
                int size;
                try {
                    id = FlashbackFormat.readVarInt(buf);
                    if (buf.readableBytes() < 4) {
                        break;
                    }
                    size = buf.readInt();
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
                if (size < 0 || size > buf.readableBytes() || id < 0 || id >= actions) {
                    break;
                }
                buf.skipBytes(size);
                if (id == FlashbackFormat.NEXT_TICK) {
                    ticks++;
                    lastGood = buf.readerIndex();
                }
            }
            try (FileChannel fc = FileChannel.open(segment, StandardOpenOption.WRITE)) {
                fc.truncate(lastGood);
            }
            return ticks;
        } catch (IndexOutOfBoundsException e) {
            return 0;
        }
    }
}
