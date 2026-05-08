package xin.sgu_server.sguprofiler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 使用与上报相同的 ingest 密钥，POST 拉取白名单并更新 {@link ProfilerAllowlistCache}。
 */
public final class AllowlistPuller {
    private static final Logger LOGGER = LoggerFactory.getLogger(AllowlistPuller.class);
    private static int ticksSincePull;

    private AllowlistPuller() {
    }

    public static void onServerStarted(MinecraftServer server) {
        ticksSincePull = 0;
        if (SGUProfiler.config().allowlistPullEnabled) {
            pullNowAsync(server);
        }
    }

    public static void onEndServerTick(MinecraftServer server) {
        SguprofilerConfig cfg = SGUProfiler.config();
        if (!cfg.allowlistPullEnabled) {
            return;
        }
        ticksSincePull++;
        if (ticksSincePull >= cfg.allowlistPollTicks) {
            ticksSincePull = 0;
            pullNowAsync(server);
        }
    }

    private static void pullNowAsync(MinecraftServer server) {
        CompletableFuture.runAsync(() -> {
            try {
                List<UUID> parsed = fetchAllowlistUuids();
                server.execute(() -> ProfilerAllowlistCache.replaceAll(parsed));
            } catch (Exception ex) {
                LOGGER.warn("[SGUProfiler] 拉取命令白名单失败: {}", ex.toString());
            }
        });
    }

    /** 必须与后端验签使用的原始 UTF-8 字节一致（勿改用 Pretty JSON）。 */
    private static final byte[] ALLOWLIST_BODY_UTF8 =
            "{\"schema\":\"allowlist_pull_v1\"}".getBytes(StandardCharsets.UTF_8);

    private static List<UUID> fetchAllowlistUuids() throws Exception {
        SguprofilerConfig cfg = SGUProfiler.config();
        byte[] secret = SGUProfiler.copyIngestSecretUtf8();
        long now = Instant.now().toEpochMilli();
        String ts = String.valueOf(now);
        byte[] tsAscii = ts.getBytes(StandardCharsets.US_ASCII);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        mac.update(tsAscii);
        mac.update((byte) '\n');
        byte[] sig = mac.doFinal(ALLOWLIST_BODY_UTF8);
        String sigHex = bytesToHex(sig);

        String host = cfg.ingestHost.strip();
        int port = cfg.ingestPort;

        StringBuilder hdr = new StringBuilder(256);
        hdr.append("POST /api/v1/profiler/allowlist/query HTTP/1.1\r\n");
        hdr.append("Host: ").append(host).append(":").append(port).append("\r\n");
        hdr.append("Content-Type: application/json; charset=UTF-8\r\n");
        hdr.append("Content-Length: ").append(ALLOWLIST_BODY_UTF8.length).append("\r\n");
        hdr.append("X-SGU-Timestamp-Ms: ").append(ts).append("\r\n");
        hdr.append("X-SGU-Signature: ").append(sigHex).append("\r\n");
        hdr.append("Connection: close\r\n");
        hdr.append("\r\n");
        byte[] headBytes = hdr.toString().getBytes(StandardCharsets.US_ASCII);

        byte[] responseBytes;
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(30_000);
            socket.connect(new InetSocketAddress(host, port), 10_000);
            OutputStream out = socket.getOutputStream();
            out.write(headBytes);
            out.write(ALLOWLIST_BODY_UTF8);
            out.flush();
            try (InputStream in = socket.getInputStream()) {
                responseBytes = in.readAllBytes();
            }
        }
        String jsonBody = httpResponseBodyUtf8(responseBytes);
        if (jsonBody.isEmpty()) {
            throw new IllegalStateException("empty_http_body");
        }
        JsonElement root = JsonParser.parseString(jsonBody);
        if (!root.isJsonObject()) {
            throw new IllegalStateException("not_object");
        }
        JsonArray arr = root.getAsJsonObject().getAsJsonArray("uuids");
        List<UUID> out = new ArrayList<>();
        if (arr != null) {
            for (JsonElement e : arr) {
                if (e != null && e.isJsonPrimitive()) {
                    out.add(UUID.fromString(e.getAsString()));
                }
            }
        }
        return out;
    }

    private static String httpResponseBodyUtf8(byte[] response) {
        if (response == null || response.length == 0) {
            return "";
        }
        for (int i = 0; i + 3 < response.length; i++) {
            if (response[i] == '\r' && response[i + 1] == '\n'
                    && response[i + 2] == '\r' && response[i + 3] == '\n') {
                return new String(response, i + 4, response.length - i - 4, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
