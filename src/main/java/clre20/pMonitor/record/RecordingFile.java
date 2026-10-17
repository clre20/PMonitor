package clre20.pMonitor.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一個錄製檔：錄製中是資料夾 {@code recordings/<UUID>/.<名稱>.recording/}，
 * 結束後壓縮成 {@code recordings/<UUID>/<名稱>.zip}（可直接放進 Flashback 的 replays 資料夾）。
 */
public final class RecordingFile {

    public enum Status { RECORDING, COMPLETED, INTERRUPTED }

    public static final String INFO_ENTRY = "pmonitor.json";
    public static final String WORK_SUFFIX = ".recording";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UUID uuid;
    private final String playerName;
    private final String baseName;
    private final Path playerDir;
    private final Path workDir;
    private final Path zipFile;
    private final int part;
    private final String worldName;
    private final String mcVersion;
    private final int dataVersion;
    private final int protocolVersion;
    private final long createdMillis;

    private RecordingFile(UUID uuid, String playerName, String baseName, Path playerDir, int part, String worldName,
                          String mcVersion, int dataVersion, int protocolVersion, long createdMillis) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.baseName = baseName;
        this.playerDir = playerDir;
        this.workDir = playerDir.resolve("." + baseName + WORK_SUFFIX);
        this.zipFile = playerDir.resolve(baseName + ".zip");
        this.part = part;
        this.worldName = worldName;
        this.mcVersion = mcVersion;
        this.dataVersion = dataVersion;
        this.protocolVersion = protocolVersion;
        this.createdMillis = createdMillis;
    }

    /** 建立新的錄製檔（決定不重複的檔名並建立工作資料夾）。 */
    public static RecordingFile create(Path recordingsDir, UUID uuid, String playerName, int part, String worldName,
                                       String mcVersion, int dataVersion, int protocolVersion) throws IOException {
        Path playerDir = recordingsDir.resolve(uuid.toString());
        Files.createDirectories(playerDir);
        long now = System.currentTimeMillis();
        String time = FILE_TIME.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));
        String base = sanitize(playerName) + "_" + time;
        String candidate = base;
        int n = 2;
        while (Files.exists(playerDir.resolve(candidate + ".zip")) || Files.exists(playerDir.resolve("." + candidate + WORK_SUFFIX))) {
            candidate = base + "_" + n++;
        }
        RecordingFile file = new RecordingFile(uuid, playerName, candidate, playerDir, part, worldName, mcVersion,
            dataVersion, protocolVersion, now);
        Files.createDirectories(file.workDir);
        return file;
    }

    /** 從工作資料夾還原（當機後修復用）。 */
    public static RecordingFile fromWorkDir(Path workDir, JsonObject info) {
        String folder = workDir.getFileName().toString();
        String base = folder.substring(1, folder.length() - WORK_SUFFIX.length());
        UUID uuid = UUID.fromString(info.get("uuid").getAsString());
        return new RecordingFile(uuid, info.get("player").getAsString(), base, workDir.getParent(),
            info.has("part") ? info.get("part").getAsInt() : 1,
            info.has("world") ? info.get("world").getAsString() : null,
            info.has("mc_version") ? info.get("mc_version").getAsString() : "",
            info.has("data_version") ? info.get("data_version").getAsInt() : 0,
            info.has("protocol_version") ? info.get("protocol_version").getAsInt() : 0,
            info.has("start") ? info.get("start").getAsLong() : System.currentTimeMillis());
    }

    private static String sanitize(String name) {
        // 基岩版玩家名稱可能以「.」開頭或含空白
        String s = name.replaceAll("[^A-Za-z0-9_\\-.]", "_");
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s.isEmpty() ? "player" : s;
    }

    /** Flashback 回放清單中顯示的名稱。 */
    public String displayName() {
        String time = DISPLAY_TIME.format(Instant.ofEpochMilli(this.createdMillis).atZone(ZoneId.systemDefault()));
        return this.playerName + " " + time + (this.part > 1 ? " (第" + this.part + "段)" : "");
    }

    /** 寫入 pmonitor.json（錄製資訊，Flashback 會忽略這個檔案）。 */
    public void writeInfo(Status status, int ticks, long start, long end) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", this.uuid.toString());
        o.addProperty("player", this.playerName);
        o.addProperty("status", status.name());
        o.addProperty("start", start);
        o.addProperty("end", end);
        o.addProperty("ticks", ticks);
        o.addProperty("part", this.part);
        if (this.worldName != null) {
            o.addProperty("world", this.worldName);
        }
        o.addProperty("mc_version", this.mcVersion);
        o.addProperty("data_version", this.dataVersion);
        o.addProperty("protocol_version", this.protocolVersion);
        Path tmp = this.workDir.resolve(INFO_ENTRY + ".tmp");
        Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
        Files.move(tmp, this.workDir.resolve(INFO_ENTRY), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 把工作資料夾壓縮成 zip，成功後刪除工作資料夾。 */
    public void finish() throws IOException {
        Path tmp = this.playerDir.resolve(this.baseName + ".zip.tmp");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(this.workDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                .filter(p -> !p.getFileName().toString().equals("metadata.json.old"))
                .forEach(files::add);
        }
        // metadata.json 放第一個
        files.sort(Comparator.comparing((Path p) -> !p.getFileName().toString().equals("metadata.json"))
            .thenComparing(Path::toString));
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.setLevel(Deflater.BEST_SPEED);
            for (Path f : files) {
                String entry = this.workDir.relativize(f).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entry));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
        Files.move(tmp, this.zipFile, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursively(this.workDir);
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }

    public UUID uuid() {
        return this.uuid;
    }

    public String playerName() {
        return this.playerName;
    }

    public String baseName() {
        return this.baseName;
    }

    public Path workDir() {
        return this.workDir;
    }

    public Path zipFile() {
        return this.zipFile;
    }

    public int part() {
        return this.part;
    }

    public String worldName() {
        return this.worldName;
    }

    public String mcVersion() {
        return this.mcVersion;
    }

    public int dataVersion() {
        return this.dataVersion;
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public long createdMillis() {
        return this.createdMillis;
    }
}
