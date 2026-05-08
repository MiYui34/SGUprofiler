package xin.sgu_server.sguprofiler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 与后端 {@code SGUPROF_INGEST_SECRET} 使用相同 UTF-8 字节做 HMAC-SHA256。
 * 优先级：{@code -Dsguprof.ingestSecret=} &gt; 环境变量 {@code SGUPROF_INGEST_SECRET}
 * &gt; Fabric {@code config/sguprofiler.json} 的 {@code ingestSecret} &gt; {@code devsecret}。
 */
public final class IngestSecretLoader {
    private static final String DEFAULT_JSON = ""
            + "{\n"
            + "  \"ingestSecret\": \"\",\n"
            + "  \"ingestHost\": \"127.0.0.1\",\n"
            + "  \"ingestPort\": 8787,\n"
            + "  \"permissionFallbackLevel\": 2,\n"
            + "  \"allowlistPullEnabled\": true,\n"
            + "  \"allowlistPollTicks\": 600,\n"
            + "  \"sampleEveryNTicks\": 1,\n"
            + "  \"minProfileNanoseconds\": 0,\n"
            + "  \"tickSampleMode\": \"STRIDE_OR_HEAVY\",\n"
            + "  \"heavyLastTickMsThreshold\": 0,\n"
            + "  \"autoMsptThreshold\": 0,\n"
            + "  \"autoProfileDurationTicks\": 200,\n"
            + "  \"scheduledProfileIntervalMinutes\": 0,\n"
            + "  \"scheduledProfileDurationTicks\": 12000,\n"
            + "  \"autoCooldownTicks\": 100\n"
            + "}\n";

    private IngestSecretLoader() {
    }

    private static byte[] devSecretUtf8() {
        return "devsecret".getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizeSecretText(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        s = stripLeadingFeff200b(s);
        s = s.strip();
        if (s.length() >= 2
                && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    /** 与 backend {@code normalized_env_sguprof_ingest_secret()} 规则一致。 */
    private static String normalizedEnvSguprofIngestSecret() {
        String raw = System.getenv("SGUPROF_INGEST_SECRET");
        if (raw == null) {
            return "";
        }
        return normalizeSecretText(raw);
    }

    private static String stripLeadingFeff200b(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\ufeff' || c == '\u200b') {
                i++;
            } else {
                break;
            }
        }
        return s.substring(i);
    }

    private static byte[] logSecretFromPath(Path fullPath, byte[] b, Logger log) {
        log.info("[SGUProfiler] ingest secret from {} (utf8_bytes={})", fullPath.toAbsolutePath(), b.length);
        return b;
    }

    /** 若不存在则在 {@code config/} 下生成空的 {@code sguprofiler.json} 模板（请填写 ingestSecret）。 */
    public static void ensureConfigTemplate(Path configDir, Logger log) {
        Path path = configDir.resolve("sguprofiler.json");
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(configDir);
                Files.writeString(path, DEFAULT_JSON, StandardCharsets.UTF_8);
                log.warn(
                        "[SGUProfiler] wrote {} — set \"ingestSecret\" to match backend SGUPROF_INGEST_SECRET (restart server after editing)",
                        path.toAbsolutePath());
            } catch (IOException ex) {
                log.warn("[SGUProfiler] could not create {}: {}", path.toAbsolutePath(), ex.toString());
            }
        }
    }

    public static byte[] resolveUtf8(Path configDir, Logger log) {
        String prop = System.getProperty("sguprof.ingestSecret");
        if (prop != null && !prop.isBlank()) {
            String n = normalizeSecretText(prop);
            if (!n.isEmpty()) {
                byte[] b = n.getBytes(StandardCharsets.UTF_8);
                log.info("[SGUProfiler] ingest secret from JVM property sguprof.ingestSecret (utf8_bytes={})", b.length);
                return b;
            }
        }

        String fromEnv = normalizedEnvSguprofIngestSecret();
        if (!fromEnv.isEmpty()) {
            byte[] b = fromEnv.getBytes(StandardCharsets.UTF_8);
            log.info("[SGUProfiler] ingest secret from environment SGUPROF_INGEST_SECRET (utf8_bytes={})", b.length);
            return b;
        }

        Path path = configDir.resolve("sguprofiler.json");
        if (Files.isRegularFile(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (root.isJsonObject()) {
                    JsonObject o = root.getAsJsonObject();
                    if (o.has("ingestSecret")) {
                        JsonElement e = o.get("ingestSecret");
                        if (e != null && e.isJsonPrimitive()) {
                            String s = e.getAsString();
                            if (s != null && !s.isBlank()) {
                                byte[] b = normalizeSecretText(s).getBytes(StandardCharsets.UTF_8);
                                return logSecretFromPath(path, b, log);
                            }
                        }
                    }
                }
            } catch (IOException | JsonParseException ex) {
                log.warn("[SGUProfiler] could not read ingestSecret from {}: {}", path.toAbsolutePath(), ex.toString());
                byte[] dev = devSecretUtf8();
                log.info("[SGUProfiler] ingest secret fallback devsecret (utf8_bytes={})", dev.length);
                return dev;
            }
            log.warn(
                    "[SGUProfiler] {} missing or blank \"ingestSecret\" — set it to backend SGUPROF_INGEST_SECRET (utf8)",
                    path.toAbsolutePath());
            byte[] dev = devSecretUtf8();
            log.info("[SGUProfiler] ingest secret fallback devsecret (utf8_bytes={})", dev.length);
            return dev;
        }

        log.warn(
                "[SGUProfiler] missing {} ; created template or configure JVM/env; using devsecret until \"ingestSecret\" is set",
                path.toAbsolutePath());
        byte[] dev = devSecretUtf8();
        log.info("[SGUProfiler] ingest secret fallback devsecret (utf8_bytes={})", dev.length);
        return dev;
    }
}
