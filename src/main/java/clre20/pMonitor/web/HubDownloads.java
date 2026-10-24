package clre20.pMonitor.web;

import clre20.httpHub.api.HttpHubService;
import clre20.httpHub.api.Request;
import clre20.httpHub.api.Response;
import clre20.httpHub.api.Route;
import clre20.pMonitor.PMonitor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 用 HttpHub 提供錄製檔下載：{@code /pmonitor/d/<金鑰>/<檔名>}。
 * <ul>
 *   <li>金鑰是 128 位元的隨機值，只發給有權限的管理員，過期後失效；最多同時保留 {@value #MAX_LINKS} 個。</li>
 *   <li>只提供建立連結時指定的檔案，網址裡的檔名只是方便辨識，不會拿來找檔案。</li>
 *   <li>HttpHub 需要把整個檔案讀進記憶體：同一個檔案同時被下載時共用同一份；剩餘記憶體不夠時回應 503。</li>
 * </ul>
 * 只有在 HttpHub 已啟用時才會建立這個類別。處理器在 HttpHub 的執行緒上執行。
 */
final class HubDownloads implements Downloads.Bridge {

    private static final int MAX_LINKS = 200;
    /** 檔案大小上限（Java 陣列與記憶體的實際限制）。 */
    private static final long MAX_FILE_BYTES = 1536L * 1024 * 1024;
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");

    private record Link(Path file, long expires) {
    }

    private final PMonitor plugin;
    private final Path recordingsDir;
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final Map<Path, WeakReference<byte[]>> shared = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile Route route;

    HubDownloads(PMonitor plugin) {
        this.plugin = plugin;
        this.recordingsDir = plugin.getDataFolder().toPath().resolve("recordings").toAbsolutePath().normalize();
    }

    private static HttpHubService hub() {
        return HttpHubService.get();
    }

    @Override
    public boolean running() {
        HttpHubService hub = hub();
        return hub != null && hub.running();
    }

    @Override
    public String create(Path file, Player viewer) {
        HttpHubService hub = hub();
        if (hub == null) {
            return null;
        }
        Route r = this.route;
        if (r == null || !r.active()) {
            r = hub.handlePrefix(this.plugin, "d", this::handle);
            this.route = r;
        }
        String base = hub.baseUrl(viewer);
        if (base == null) {
            base = fallbackBase(hub.port());
        }
        if (base == null) {
            return null;
        }
        purge();
        byte[] b = new byte[16];
        this.random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        long expires = System.currentTimeMillis() + this.plugin.config().downloadExpireMinutes * 60_000L;
        this.links.put(token, new Link(file.toAbsolutePath().normalize(), expires));
        String name = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return base + r.path() + "/" + token + "/" + name;
    }

    @Override
    public void clear() {
        this.links.clear();
        this.shared.clear();
        Route r = this.route;
        this.route = null;
        if (r != null && r.active()) {
            r.remove();
        }
    }

    /** 主控台建立連結、HttpHub 沒設定 public-url 時：用伺服器 IP 與 HttpHub 的端口。 */
    private static String fallbackBase(int port) {
        String host = Bukkit.getIp();
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            try {
                host = InetAddress.getLocalHost().getHostAddress();
            } catch (IOException e) {
                return null;
            }
        }
        return "http://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    /** 移除過期的連結；數量超過上限時移除最早過期的。 */
    private void purge() {
        long now = System.currentTimeMillis();
        this.links.values().removeIf(l -> l.expires < now);
        while (this.links.size() >= MAX_LINKS) {
            String oldest = null;
            long min = Long.MAX_VALUE;
            for (Map.Entry<String, Link> e : this.links.entrySet()) {
                if (e.getValue().expires < min) {
                    min = e.getValue().expires;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) {
                break;
            }
            this.links.remove(oldest);
        }
        Iterator<WeakReference<byte[]>> it = this.shared.values().iterator();
        while (it.hasNext()) {
            if (it.next().get() == null) {
                it.remove();
            }
        }
    }

    // ================================================================ HttpHub 執行緒

    private Response handle(Request req) throws IOException {
        if (!this.plugin.isEnabled() || !this.plugin.config().downloadEnabled) {
            return text(404, "web.not-found");
        }
        if (!"GET".equals(req.method()) && !"HEAD".equals(req.method())) {
            return Response.status(405).header("Allow", "GET, HEAD");
        }
        // subPath 例如 /<金鑰>/<檔名>
        String sub = req.subPath();
        int start = sub.startsWith("/") ? 1 : 0;
        int end = sub.indexOf('/', start);
        String token = end < 0 ? sub.substring(start) : sub.substring(start, end);
        if (!TOKEN.matcher(token).matches()) {
            return text(404, "web.not-found");
        }
        Link link = this.links.get(token);
        if (link == null || link.expires < System.currentTimeMillis()) {
            if (link != null) {
                this.links.remove(token);
            }
            return text(404, "web.expired");
        }
        Path file = link.file;
        if (!file.startsWith(this.recordingsDir) || !file.getFileName().toString().endsWith(".zip") || !Files.isRegularFile(file)) {
            return text(404, "web.not-found");
        }
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            return text(413, "web.too-large");
        }
        byte[] data = sharedBytes(file, size);
        if (data == null) {
            return text(503, "web.busy").header("Retry-After", "30");
        }
        String name = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
        return Response.of(200, data, "application/zip")
            .header("Content-Disposition", "attachment; filename*=UTF-8''" + name)
            .header("Cache-Control", "no-store");
    }

    /** 讀取檔案（同一個檔案同時下載時共用）；剩餘記憶體不夠時回傳 null。 */
    private synchronized byte[] sharedBytes(Path file, long size) throws IOException {
        WeakReference<byte[]> ref = this.shared.get(file);
        byte[] data = ref == null ? null : ref.get();
        if (data != null && data.length == size) {
            return data;
        }
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (size > free / 2) {
            return null;
        }
        data = Files.readAllBytes(file);
        this.shared.put(file, new WeakReference<>(data));
        return data;
    }

    private Response text(int status, String key) {
        return Response.text(status, this.plugin.messages().plain(key));
    }
}
