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

/**
 * 載入 config.yml / messages.yml，並在插件更新後自動補上新設定：
 * 以插件內建的預設檔（含註解）為底，填回使用者原本的值，舊檔備份為 .bak。
 */
public final class ConfigFiles {

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

        int currentVersion = current.getInt("config-version", 0);
        int defaultVersion = defaults.getInt("config-version", 0);
        if (currentVersion != defaultVersion) {
            try {
                Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
                YamlConfiguration merged = loadDefaults(plugin, name);
                for (String key : current.getKeys(true)) {
                    if (current.isConfigurationSection(key) || key.equals("config-version")) {
                        continue;
                    }
                    if (merged.contains(key) && !merged.isConfigurationSection(key)) {
                        merged.set(key, current.get(key));
                    }
                }
                merged.set("config-version", defaultVersion);
                merged.save(file);
                plugin.getLogger().info(name + " 已更新到第 " + defaultVersion + " 版（舊檔備份為 " + name + ".bak）");
                current = merged;
            } catch (IOException e) {
                plugin.getLogger().warning("更新 " + name + " 失敗：" + e.getMessage());
            }
        }
        current.setDefaults(defaults);
        return current;
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
