package clre20.pMonitor.config;

import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;

/** config.yml 的內容（重新載入時整個換新）。 */
public final class PluginConfig {

    public final String prefix;
    public final boolean updateCheck;
    public final boolean updateNotifyOnJoin;
    public final boolean notifyPlayer;
    public final int maxDurationMinutes;
    public final long maxFileSizeBytes;
    public final boolean recordOwnBlockEffects;
    public final int retentionDays;
    public final long warnSizeBytes;
    public final long stopSizeBytes;
    public final int warnOpLevel;
    public final int warnIntervalMinutes;
    public final boolean downloadEnabled;
    public final String downloadHost;
    public final int downloadPort;
    public final String downloadPublicUrl;
    public final int downloadExpireMinutes;
    public final int inputTimeout;
    public final String cancelWord;
    public final boolean soundsEnabled;
    public final Sound soundClick;
    public final Sound soundSuccess;
    public final Sound soundFail;

    public PluginConfig(YamlConfiguration c) {
        this.prefix = c.getString("prefix", "&8[&bPMonitor&8] &r");
        this.updateCheck = c.getBoolean("update-checker.enabled", true);
        this.updateNotifyOnJoin = c.getBoolean("update-checker.notify-on-join", true);
        this.notifyPlayer = c.getBoolean("recording.notify-player", false);
        this.maxDurationMinutes = Math.max(0, c.getInt("recording.max-duration-minutes", 120));
        this.maxFileSizeBytes = Math.max(0, c.getLong("recording.max-file-size-mb", 1024)) * 1024L * 1024L;
        this.recordOwnBlockEffects = c.getBoolean("recording.record-own-block-effects", true);
        this.retentionDays = Math.max(0, c.getInt("storage.retention-days", 30));
        this.warnSizeBytes = gb(c.getDouble("storage.warn-size-gb", 5));
        this.stopSizeBytes = gb(c.getDouble("storage.stop-size-gb", 10));
        this.warnOpLevel = c.getInt("storage.warn-op-level", 2);
        this.warnIntervalMinutes = Math.max(1, c.getInt("storage.warn-interval-minutes", 10));
        this.downloadEnabled = c.getBoolean("download.enabled", false);
        this.downloadHost = c.getString("download.host", "0.0.0.0");
        this.downloadPort = c.getInt("download.port", 52012);
        this.downloadPublicUrl = c.getString("download.public-url", "");
        this.downloadExpireMinutes = Math.max(1, c.getInt("download.link-expire-minutes", 10));
        this.inputTimeout = Math.max(5, c.getInt("chat-input.timeout", 60));
        this.cancelWord = c.getString("chat-input.cancel-word", "取消");
        this.soundsEnabled = c.getBoolean("sounds.enabled", true);
        this.soundClick = sound(c.getString("sounds.click"), "ui.button.click");
        this.soundSuccess = sound(c.getString("sounds.success"), "entity.player.levelup");
        this.soundFail = sound(c.getString("sounds.fail"), "entity.villager.no");
    }

    private static long gb(double v) {
        return v <= 0 ? 0 : (long) (v * 1024 * 1024 * 1024);
    }

    private static Sound sound(String name, String fallbackKey) {
        Sound s = null;
        if (name != null && !name.isBlank()) {
            String key = name.toLowerCase(Locale.ROOT);
            if (!key.contains(".") && !key.contains(":")) {
                key = key.replace('_', '.');
            }
            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk != null) {
                s = Registry.SOUNDS.get(nk);
            }
            if (s == null) {
                // 舊式名稱如 UI_BUTTON_CLICK 轉成 ui.button.click 不一定正確，逐一比對
                for (Sound candidate : Registry.SOUNDS) {
                    NamespacedKey ck = Registry.SOUNDS.getKey(candidate);
                    if (ck != null && ck.getKey().replace('.', '_').equalsIgnoreCase(name)) {
                        s = candidate;
                        break;
                    }
                }
            }
        }
        if (s == null) {
            s = Registry.SOUNDS.get(NamespacedKey.minecraft(fallbackKey));
        }
        return s;
    }
}
