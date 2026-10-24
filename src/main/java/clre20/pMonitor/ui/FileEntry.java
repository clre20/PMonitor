package clre20.pMonitor.ui;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.Messages;
import clre20.pMonitor.record.Recorder;
import clre20.pMonitor.record.RecordingFile;
import clre20.pMonitor.storage.RecordingIndex;
import clre20.pMonitor.util.Fmt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 介面與指令用的錄製檔資料（已存檔或錄製中）。 */
public record FileEntry(String name, UUID uuid, String player, long start, long durationMillis, long size,
                        String world, String mcVersion, RecordingFile.Status status, int part, Path path, Recorder recorder) {

    public boolean recording() {
        return this.recorder != null;
    }

    static FileEntry of(RecordingIndex.Info i) {
        return new FileEntry(i.name(), i.uuid(), i.player(), i.start(), i.durationMillis(), i.size(), i.world(),
            i.mcVersion(), i.status(), i.part(), i.path(), null);
    }

    static FileEntry of(Recorder r) {
        RecordingFile f = r.file();
        return new FileEntry(f.baseName(), r.uuid(), r.playerName(), r.startMillis(),
            System.currentTimeMillis() - r.startMillis(), r.bytesWritten(), f.worldName(), f.mcVersion(),
            RecordingFile.Status.RECORDING, f.part(), null, r);
    }

    /** 錄製中的排最前面，其餘新到舊。uuid 為 null 表示全部玩家。 */
    public static List<FileEntry> list(PMonitor plugin, UUID uuid) {
        List<FileEntry> out = new ArrayList<>();
        for (Recorder r : plugin.recordings().activeRecorders()) {
            if (uuid == null || r.uuid().equals(uuid)) {
                out.add(of(r));
            }
        }
        for (RecordingIndex.Info i : uuid == null ? plugin.index().all() : plugin.index().of(uuid)) {
            out.add(of(i));
        }
        return out;
    }

    public static FileEntry find(PMonitor plugin, String name) {
        for (Recorder r : plugin.recordings().activeRecorders()) {
            if (r.file().baseName().equalsIgnoreCase(name)) {
                return of(r);
            }
        }
        RecordingIndex.Info i = plugin.index().get(name);
        if (i != null) {
            return of(i);
        }
        for (RecordingIndex.Info info : plugin.index().all()) {
            if (info.name().equalsIgnoreCase(name)) {
                return of(info);
            }
        }
        return null;
    }

    public String statusText(Messages m) {
        return m.raw("status." + this.status.name().toLowerCase(java.util.Locale.ROOT));
    }

    public Object[] placeholders(Messages m) {
        return new Object[]{
            "file", this.name,
            "player", this.player,
            "time", Fmt.dateTime(this.start),
            "duration", Fmt.duration(this.durationMillis),
            "size", Fmt.size(this.size),
            "world", this.world == null ? "-" : this.world,
            "version", this.mcVersion == null || this.mcVersion.isEmpty() ? "-" : this.mcVersion,
            "status", statusText(m),
            "part", this.part
        };
    }
}
