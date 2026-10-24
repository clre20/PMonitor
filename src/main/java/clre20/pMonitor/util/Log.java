package clre20.pMonitor.util;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.Messages;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主控台紀錄：文字放在 messages.yml 的 console 區段（key 不含 "console." 前綴）。
 * 插件啟用期間有效；關閉後改用預設 logger 並直接輸出 key。
 */
public final class Log {

    private static volatile PMonitor plugin;

    private Log() {
    }

    public static void init(PMonitor p) {
        plugin = p;
    }

    public static void clear() {
        plugin = null;
    }

    public static void info(String key, Object... args) {
        emit(Level.INFO, key, null, args);
    }

    public static void warn(String key, Object... args) {
        emit(Level.WARNING, key, null, args);
    }

    /** 附上例外堆疊（只用在真正的錯誤）。 */
    public static void severe(String key, Throwable t, Object... args) {
        emit(Level.SEVERE, key, t, args);
    }

    /** messages.yml 任意鍵的純文字（不含顏色碼）；插件未啟用時回傳 fallback。 */
    public static String message(String key, String fallback, Object... args) {
        PMonitor p = plugin;
        Messages m = p == null ? null : p.messages();
        return m == null ? Messages.replace(fallback, args) : m.plain(key, args);
    }

    /** 取得主控台文字（不輸出）。 */
    public static String text(String key, Object... args) {
        PMonitor p = plugin;
        Messages m = p == null ? null : p.messages();
        if (m == null) {
            StringBuilder sb = new StringBuilder(key);
            for (int i = 1; i < args.length; i += 2) {
                sb.append(' ').append(args[i]);
            }
            return sb.toString();
        }
        return m.plain("console." + key, args);
    }

    private static void emit(Level level, String key, Throwable t, Object... args) {
        PMonitor p = plugin;
        Logger logger = p == null ? Logger.getLogger("PMonitor") : p.getLogger();
        logger.log(level, text(key, args), t);
    }
}
