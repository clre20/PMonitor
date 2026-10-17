package clre20.pMonitor.web;

import clre20.pMonitor.PMonitor;
import clre20.pMonitor.config.PluginConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 錄製檔下載（預設關閉）。每個連結帶一次性的隨機金鑰，過期後失效。
 */
public final class DownloadServer {

    private record Link(Path file, long expires) {
    }

    private final PMonitor plugin;
    private final Map<String, Link> links = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;
    private String boundHost;
    private int boundPort;

    public DownloadServer(PMonitor plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return this.server != null;
    }

    /** 依設定啟動、重新啟動或關閉。 */
    public void applyConfig() {
        PluginConfig c = this.plugin.config();
        if (!c.downloadEnabled) {
            stop();
            return;
        }
        if (this.server != null && c.downloadHost.equals(this.boundHost) && c.downloadPort == this.boundPort) {
            return;
        }
        stop();
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(c.downloadHost, c.downloadPort), 16);
            s.createContext("/d/", this::handle);
            s.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "PMonitor-Download");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            this.server = s;
            this.boundHost = c.downloadHost;
            this.boundPort = c.downloadPort;
            this.plugin.getLogger().info("下載伺服器已啟動：" + c.downloadHost + ":" + c.downloadPort);
        } catch (IOException e) {
            this.plugin.getLogger().warning("無法啟動下載伺服器（" + c.downloadHost + ":" + c.downloadPort + "）：" + e.getMessage());
        }
    }

    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        this.links.clear();
    }

    /** 建立下載連結；伺服器未啟動回傳 null。 */
    public String createLink(Path file) {
        if (this.server == null) {
            return null;
        }
        byte[] b = new byte[16];
        this.random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        long expires = System.currentTimeMillis() + this.plugin.config().downloadExpireMinutes * 60_000L;
        this.links.put(token, new Link(file, expires));
        this.links.values().removeIf(l -> l.expires < System.currentTimeMillis());
        return baseUrl() + "/d/" + token + "/" + URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String baseUrl() {
        String url = this.plugin.config().downloadPublicUrl;
        if (url != null && !url.isBlank()) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        String host = Bukkit.getIp();
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            host = this.boundHost;
        }
        if (host == null || host.isBlank() || host.equals("0.0.0.0")) {
            try {
                host = InetAddress.getLocalHost().getHostAddress();
            } catch (IOException e) {
                host = "localhost";
            }
        }
        return "http://" + host + ":" + this.boundPort;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String[] parts = path.split("/");
            String token = parts.length >= 3 ? parts[2] : "";
            Link link = this.links.get(token);
            if (link == null || link.expires < System.currentTimeMillis() || !Files.isRegularFile(link.file)) {
                byte[] msg = "連結無效或已過期".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                ex.sendResponseHeaders(404, msg.length);
                ex.getResponseBody().write(msg);
                return;
            }
            long size = Files.size(link.file);
            String name = link.file.getFileName().toString();
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
            ex.sendResponseHeaders(200, size);
            try (OutputStream os = ex.getResponseBody()) {
                Files.copy(link.file, os);
            }
        }
    }
}
