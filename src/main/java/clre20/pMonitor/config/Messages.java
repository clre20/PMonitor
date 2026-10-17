package clre20.pMonitor.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/** messages.yml 的訊息；支援 & 顏色碼與 {名稱} 佔位符。 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final YamlConfiguration yaml;
    private final String prefix;

    public Messages(YamlConfiguration yaml, String prefix) {
        this.yaml = yaml;
        this.prefix = prefix;
    }

    /** 取得原始字串並替換佔位符（args 為 名稱, 值, 名稱, 值…）。 */
    public String raw(String key, Object... args) {
        String s = this.yaml.getString(key);
        if (s == null) {
            s = key;
        }
        return replace(s, args);
    }

    public List<String> rawList(String key, Object... args) {
        List<String> out = new ArrayList<>();
        for (String s : this.yaml.getStringList(key)) {
            out.add(replace(s, args));
        }
        return out;
    }

    public static String replace(String s, Object... args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            s = s.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return s;
    }

    public Component component(String key, Object... args) {
        return color(raw(key, args));
    }

    /** 物品名稱與說明用：去掉預設的斜體。 */
    public Component item(String key, Object... args) {
        return color(raw(key, args)).decoration(TextDecoration.ITALIC, false);
    }

    public List<Component> itemLore(String key, Object... args) {
        List<Component> out = new ArrayList<>();
        for (String s : rawList(key, args)) {
            out.add(color(s).decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    public static Component color(String s) {
        return LEGACY.deserialize(s);
    }

    /** 去掉顏色碼的純文字（給主控台紀錄用）。 */
    public String plain(String key, Object... args) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(color(raw(key, args)));
    }

    public void send(CommandSender to, String key, Object... args) {
        String s = raw(key, args);
        if (s.isEmpty()) {
            return;
        }
        to.sendMessage(color(this.prefix + s));
    }

    /** 不加前綴（清單內容等）。 */
    public void sendPlain(CommandSender to, String key, Object... args) {
        String s = raw(key, args);
        if (s.isEmpty()) {
            return;
        }
        to.sendMessage(color(s));
    }

    public Component prefixed(Component c) {
        return color(this.prefix).append(c);
    }
}
