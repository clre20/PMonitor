package clre20.pMonitor.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 讀取插件資料夾裡的 yml（啟動與 /cpm reload 時）。
 * <p>
 * 每次讀取都會跟內建的預設檔比對：有缺少的項目時，以預設檔為底（保留註解）填回使用者原本的值後寫回檔案，
 * 新版已不使用的項目會移除，舊檔備份為 .bak。
 * <p>
 * 這裡的訊息在 messages.yml 讀取之前就可能出現，所以直接寫在程式裡。
 */
public final class ConfigFiles {

    /** 舊設定 → 新位置（只在新位置沒有值時搬過去）。 */
    private static final Map<String, String> MOVED = Map.of(
        "update-checker.enabled", "update-checker.check-on-startup"
    );

    private ConfigFiles() {
    }

    public static YamlConfiguration load(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration defaults = loadDefaults(plugin, name);
        YamlConfiguration current = new YamlConfiguration();
        try {
            current.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe(name + " 格式錯誤，暫時使用預設值：" + e.getMessage());
            return defaults;
        }

        int missing = 0;
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isConfigurationSection(key) && !current.contains(key, true)) {
                missing++;
            }
        }
        boolean versionChanged = current.getInt("config-version", 0) != defaults.getInt("config-version", 0);
        if (missing > 0 || versionChanged) {
            try {
                Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
                YamlConfiguration merged = loadDefaults(plugin, name);
                List<YamlConfiguration> legacy = legacyDefaults(plugin, name, defaults.getInt("config-version", 0));
                for (String key : current.getKeys(true)) {
                    if (current.isConfigurationSection(key) || key.equals("config-version")) {
                        continue;
                    }
                    // 跟舊版預設值完全相同 = 沒有自訂過，改用新版的預設值
                    if (isOldDefault(legacy, key, current.get(key))) {
                        continue;
                    }
                    String target = MOVED.getOrDefault(key, key);
                    if (!target.equals(key) && current.contains(target, true)) {
                        continue;
                    }
                    // 新版已經不用的項目不寫回去
                    if (merged.contains(target, true) && !merged.isConfigurationSection(target)) {
                        merged.set(target, current.get(key));
                    }
                }
                merged.save(file);
                plugin.getLogger().info(name + (missing > 0 ? "：已補上 " + missing + " 個缺少的項目" : "：已更新到新版格式") + "（舊檔備份為 " + name + ".bak）");
                current = merged;
            } catch (IOException e) {
                plugin.getLogger().warning("更新 " + name + " 失敗：" + e.getMessage());
            }
        }
        current.setDefaults(defaults);
        return current;
    }

    /** 插件內建的舊版預設檔（legacy/messages-v1.yml …），用來判斷使用者有沒有改過某個值。 */
    private static List<YamlConfiguration> legacyDefaults(JavaPlugin plugin, String name, int currentVersion) {
        List<YamlConfiguration> out = new ArrayList<>();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        for (int v = 1; v < currentVersion; v++) {
            String path = "legacy/" + base + "-v" + v + ".yml";
            if (plugin.getResource(path) != null) {
                out.add(loadDefaults(plugin, path));
            }
        }
        return out;
    }

    private static boolean isOldDefault(List<YamlConfiguration> legacy, String key, Object value) {
        for (YamlConfiguration old : legacy) {
            Object o = old.get(key);
            if (o != null && Objects.equals(String.valueOf(o), String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    public static YamlConfiguration loadDefaults(JavaPlugin plugin, String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("讀取內建 " + name + " 失敗：" + e.getMessage());
        }
        return yaml;
    }
}
