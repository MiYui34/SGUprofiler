package xin.sgu_server.sguprofiler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import xin.sgu_server.sguprofiler.profiler.TickSampleMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 自 {@code config/sguprofiler.json} 读取；与 {@link IngestSecretLoader}
 * 共用同一文件，缺失字段使用默认值。
 */
public final class SguprofilerConfig {
    public final int sampleEveryNTicks;
    public final long minProfileNanoseconds;
    public final double autoMsptThreshold;
    public final int autoProfileDurationTicks;
    public final int scheduledProfileDurationTicks;
    public final int scheduledProfileIntervalMinutes;
    public final int autoCooldownTicks;
    public final TickSampleMode tickSampleMode;
    public final double heavyLastTickMsThreshold;
    public final String ingestHost;
    public final int ingestPort;
    public final int permissionFallbackLevel;
    public final boolean allowlistPullEnabled;
    public final int allowlistPollTicks;

    public SguprofilerConfig(
            int sampleEveryNTicks,
            long minProfileNanoseconds,
            double autoMsptThreshold,
            int autoProfileDurationTicks,
            int scheduledProfileDurationTicks,
            int scheduledProfileIntervalMinutes,
            int autoCooldownTicks,
            TickSampleMode tickSampleMode,
            double heavyLastTickMsThreshold,
            String ingestHost,
            int ingestPort,
            int permissionFallbackLevel,
            boolean allowlistPullEnabled,
            int allowlistPollTicks) {
        this.sampleEveryNTicks = sampleEveryNTicks;
        this.minProfileNanoseconds = minProfileNanoseconds;
        this.autoMsptThreshold = autoMsptThreshold;
        this.autoProfileDurationTicks = autoProfileDurationTicks;
        this.scheduledProfileDurationTicks = scheduledProfileDurationTicks;
        this.scheduledProfileIntervalMinutes = scheduledProfileIntervalMinutes;
        this.autoCooldownTicks = autoCooldownTicks;
        this.tickSampleMode = tickSampleMode;
        this.heavyLastTickMsThreshold = heavyLastTickMsThreshold;
        this.ingestHost = ingestHost;
        this.ingestPort = ingestPort;
        this.permissionFallbackLevel = permissionFallbackLevel;
        this.allowlistPullEnabled = allowlistPullEnabled;
        this.allowlistPollTicks = allowlistPollTicks;
    }

    public static SguprofilerConfig defaults() {
        return new SguprofilerConfig(
                1,
                0L,
                0.0,
                200,
                12000,
                0,
                100,
                TickSampleMode.STRIDE_OR_HEAVY,
                0.0,
                "127.0.0.1",
                8787,
                2,
                true,
                600);
    }

    public static SguprofilerConfig load(Path configDir, Logger log) {
        Path path = configDir.resolve("sguprofiler.json");
        SguprofilerConfig def = defaults();
        if (!Files.isRegularFile(path)) {
            return def;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return def;
            }
            JsonObject o = root.getAsJsonObject();
            int stride = getInt(o, "sampleEveryNTicks", def.sampleEveryNTicks);
            stride = Math.max(1, stride);
            long minNs = getLong(o, "minProfileNanoseconds", def.minProfileNanoseconds);
            double autoMspt = getDouble(o, "autoMsptThreshold", def.autoMsptThreshold);
            int autoDur = getInt(o, "autoProfileDurationTicks", def.autoProfileDurationTicks);
            int schedDur = getInt(o, "scheduledProfileDurationTicks", def.scheduledProfileDurationTicks);
            int schedMin = getInt(o, "scheduledProfileIntervalMinutes", def.scheduledProfileIntervalMinutes);
            int cool = getInt(o, "autoCooldownTicks", def.autoCooldownTicks);
            TickSampleMode mode = parseMode(getString(o, "tickSampleMode", "STRIDE_OR_HEAVY"), def.tickSampleMode);
            double heavy = getDouble(o, "heavyLastTickMsThreshold", def.heavyLastTickMsThreshold);
            String host = getString(o, "ingestHost", def.ingestHost);
            if (host.isBlank()) {
                host = def.ingestHost;
            }
            int port = getInt(o, "ingestPort", def.ingestPort);
            if (port <= 0 || port > 65535) {
                port = def.ingestPort;
                log.warn("[SGUProfiler] invalid ingestPort in config, using {}", port);
            }
            int perm = getInt(o, "permissionFallbackLevel", def.permissionFallbackLevel);
            perm = Math.clamp(perm, 0, 4);
            boolean allowPull = getBool(o, "allowlistPullEnabled", def.allowlistPullEnabled);
            int pollTicks = getInt(o, "allowlistPollTicks", def.allowlistPollTicks);
            pollTicks = Math.clamp(pollTicks, 20, 72000);

            if (mode == TickSampleMode.HEAVY_ONLY && heavy <= 0.0) {
                log.warn(
                        "[SGUProfiler] tickSampleMode HEAVY_ONLY requires heavyLastTickMsThreshold > 0; using STRIDE_ONLY with sampleEveryNTicks={}",
                        stride);
                mode = TickSampleMode.STRIDE_ONLY;
            }
            if (mode == TickSampleMode.STRIDE_AND_HEAVY && heavy <= 0.0) {
                log.warn(
                        "[SGUProfiler] tickSampleMode STRIDE_AND_HEAVY needs heavyLastTickMsThreshold > 0; using STRIDE_ONLY");
                mode = TickSampleMode.STRIDE_ONLY;
            }

            return new SguprofilerConfig(
                    stride,
                    minNs,
                    autoMspt,
                    autoDur,
                    schedDur,
                    schedMin,
                    cool,
                    mode,
                    heavy,
                    host,
                    port,
                    perm,
                    allowPull,
                    pollTicks);
        } catch (IOException | JsonParseException ex) {
            log.warn("[SGUProfiler] could not parse {}, using defaults: {}", path.toAbsolutePath(), ex.toString());
            return def;
        }
    }

    private static TickSampleMode parseMode(String raw, TickSampleMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return TickSampleMode.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static boolean getBool(JsonObject o, String key, boolean d) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return d;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception ex) {
            return d;
        }
    }

    private static int getInt(JsonObject o, String key, int d) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return d;
        }
        try {
            return o.get(key).getAsInt();
        } catch (Exception ex) {
            return d;
        }
    }

    private static long getLong(JsonObject o, String key, long d) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return d;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Exception ex) {
            return d;
        }
    }

    private static double getDouble(JsonObject o, String key, double d) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return d;
        }
        try {
            return o.get(key).getAsDouble();
        } catch (Exception ex) {
            return d;
        }
    }

    private static String getString(JsonObject o, String key, String d) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
            return d;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception ex) {
            return d;
        }
    }
}
