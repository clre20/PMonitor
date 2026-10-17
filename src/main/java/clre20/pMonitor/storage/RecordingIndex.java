package clre20.pMonitor.storage;

import clre20.pMonitor.record.RecordingFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 已完成的錄製檔清單（掃描 recordings 資料夾內的 zip）。 */
public final class RecordingIndex {

    /** 一個已存檔的錄製檔。 */
    public record Info(UUID uuid, String player, String name, Path path, long size, long start, long end,
                       int ticks, String world, String mcVersion, RecordingFile.Status status, int part) {
        public long durationMillis() {
            return this.ticks > 0 ? this.ticks * 50L : Math.max(0, this.end - this.start);
        }
    }

    private final Path dir;
    private final Logger logger;
    private final Map<String, Info> byName = new ConcurrentHashMap<>();
    private volatile boolean scanned;

    public RecordingIndex(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    /** 掃描全部錄製檔（在背景執行緒呼叫）。 */
    public void scan() {
        Map<String, Info> found = new ConcurrentHashMap<>();
        if (Files.isDirectory(this.dir)) {
            try (DirectoryStream<Path> players = Files.newDirectoryStream(this.dir)) {
                for (Path playerDir : players) {
                    if (!Files.isDirectory(playerDir)) {
                        continue;
                    }
                    try (DirectoryStream<Path> zips = Files.newDirectoryStream(playerDir, "*.zip")) {
                        for (Path zip : zips) {
                            Info info = read(zip);
                            if (info != null) {
                                found.put(info.name, info);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                this.logger.warning("掃描錄製檔失敗：" + e.getMessage());
            }
        }
        this.byName.clear();
        this.byName.putAll(found);
        this.scanned = true;
    }

    public Info read(Path zip) {
        String fileName = zip.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - 4);
        try {
            long size = Files.size(zip);
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                ZipEntry entry = zf.getEntry(RecordingFile.INFO_ENTRY);
                if (entry == null) {
                    // 不是 PMonitor 產生的檔案（例如手動放進來的 Flashback 回放），仍列出
                    UUID uuid = parseUuid(zip.getParent().getFileName().toString());
                    long t = Files.getLastModifiedTime(zip).toMillis();
                    return new Info(uuid, name, name, zip, size, t, t, 0, null, "", RecordingFile.Status.COMPLETED, 1);
                }
                JsonObject o;
                try (InputStream in = zf.getInputStream(entry)) {
                    o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
                RecordingFile.Status status;
                try {
                    status = RecordingFile.Status.valueOf(o.get("status").getAsString());
                } catch (Exception e) {
                    status = RecordingFile.Status.COMPLETED;
                }
                if (status == RecordingFile.Status.RECORDING) {
                    status = RecordingFile.Status.INTERRUPTED;
                }
                return new Info(
                    UUID.fromString(o.get("uuid").getAsString()),
                    o.get("player").getAsString(),
                    name, zip, size,
                    o.get("start").getAsLong(),
                    o.get("end").getAsLong(),
                    o.has("ticks") ? o.get("ticks").getAsInt() : 0,
                    o.has("world") ? o.get("world").getAsString() : null,
                    o.has("mc_version") ? o.get("mc_version").getAsString() : "",
                    status,
                    o.has("part") ? o.get("part").getAsInt() : 1);
            }
        } catch (Exception e) {
            this.logger.warning("無法讀取錄製檔 " + zip.getFileName() + "：" + e.getMessage());
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return new UUID(0, 0);
        }
    }

    public void add(Path zip) {
        Info info = read(zip);
        if (info != null) {
            this.byName.put(info.name, info);
        }
    }

    public void remove(String name) {
        this.byName.remove(name);
    }

    public Info get(String name) {
        return this.byName.get(name);
    }

    public boolean isScanned() {
        return this.scanned;
    }

    /** 全部錄製檔，新到舊。 */
    public List<Info> all() {
        List<Info> list = new ArrayList<>(this.byName.values());
        list.sort(Comparator.comparingLong(Info::start).reversed());
        return list;
    }

    public List<Info> of(UUID uuid) {
        List<Info> list = new ArrayList<>();
        for (Info i : this.byName.values()) {
            if (i.uuid.equals(uuid)) {
                list.add(i);
            }
        }
        list.sort(Comparator.comparingLong(Info::start).reversed());
        return list;
    }

    public int count(UUID uuid) {
        int n = 0;
        for (Info i : this.byName.values()) {
            if (i.uuid.equals(uuid)) {
                n++;
            }
        }
        return n;
    }

    public long totalSize() {
        long total = 0;
        for (Info i : this.byName.values()) {
            total += i.size;
        }
        return total;
    }
}
