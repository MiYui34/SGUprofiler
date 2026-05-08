package xin.sgu_server.sguprofiler.profiler;

import carpet.utils.Messenger;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.world.World;
import xin.sgu_server.sguprofiler.ServerMspt;
import xin.sgu_server.sguprofiler.SguprofilerConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class SGUProfiler {
    private static final Object SNAPSHOT_ID_LOCK = new Object();

    /** 单次报告与上报快照：按合计 ms/Tick 降序，最多展示的「实体 × 维度」汇总条数。 */
    private static final int REPORT_TOP_AGGREGATES = 10;

    private static String lastSnapshotDay = "";
    private static int snapshotCountThisDay = 0;

    private static byte[] ingestSecretUtf8 = "devsecret".getBytes(StandardCharsets.UTF_8);

    public static void setIngestSecretUtf8(byte[] secretUtf8) {
        if (secretUtf8 != null && secretUtf8.length > 0) {
            ingestSecretUtf8 = Arrays.copyOf(secretUtf8, secretUtf8.length);
        }
    }

    private static SguprofilerConfig config = SguprofilerConfig.defaults();

    public static void applyConfig(SguprofilerConfig c) {
        if (c != null) {
            config = c;
        }
    }

    public static SguprofilerConfig config() {
        return config;
    }

    /** 供拉取白名单等与 ingest 同密钥的 HMAC 调用。 */
    public static byte[] copyIngestSecretUtf8() {
        return Arrays.copyOf(ingestSecretUtf8, ingestSecretUtf8.length);
    }

    public static int permissionFallbackLevel() {
        return config.permissionFallbackLevel;
    }

    /** {@code true} 时仅统计 Carpet {@code EntityPlayerActionPack} 的 PLAYER_ACTION 分项。 */
    public static boolean botProfilingOnly = false;

    /** 上一完整游戏刻墙钟耗时（毫秒），用于重刻采样判定。 */
    private static double lastCompletedWallTickMs = 0.0;

    private static long wallSpanStartNano = 0L;

    /** {@code null} 表示三个维度都统计 */
    public static Set<RegistryKey<World>> allowedDimensions = null;

    public static boolean isRunning = false;
    public static boolean currentTickSampled = false;

    public static int sessionCompletedTicks = 0;
    public static int sampledTickCount = 0;

    private static int sessionTickLimit = Integer.MAX_VALUE;

    private enum SessionKind {
        MANUAL, AUTO_MSPT, AUTO_SCHEDULED
    }

    private static SessionKind sessionKind = SessionKind.MANUAL;

    private static long autoCooldownUntilServerTick = Long.MIN_VALUE;
    private static long lastScheduledWallTimeMs = System.currentTimeMillis();

    public static void onServerStarted(MinecraftServer server) {
        lastScheduledWallTimeMs = System.currentTimeMillis();
    }

    public static void onStartServerTick(MinecraftServer server) {
        wallSpanStartNano = System.nanoTime();
        if (!isRunning) {
            currentTickSampled = false;
            return;
        }
        int stride = Math.max(1, config.sampleEveryNTicks);
        boolean byStride = (sessionCompletedTicks % stride) == 0;
        boolean byHeavy = config.heavyLastTickMsThreshold > 0.0
                && lastCompletedWallTickMs >= config.heavyLastTickMsThreshold;
        currentTickSampled =
                switch (config.tickSampleMode) {
                    case STRIDE_ONLY -> byStride;
                    case HEAVY_ONLY -> byHeavy;
                    case STRIDE_OR_HEAVY -> byStride || byHeavy;
                    case STRIDE_AND_HEAVY -> byStride && byHeavy;
                };
        if (currentTickSampled) {
            sampledTickCount++;
        }
    }

    public static void onEndServerTick(MinecraftServer server) {
        if (wallSpanStartNano != 0L) {
            lastCompletedWallTickMs = (System.nanoTime() - wallSpanStartNano) / 1_000_000.0;
        }
        if (isRunning) {
            sessionCompletedTicks++;
            if (sessionKind != SessionKind.MANUAL
                    && sessionCompletedTicks >= sessionTickLimit) {
                stopProfiling(server.getCommandSource().withSilent(), true);
            }
            return;
        }

        long nowTick = server.getTicks();
        if (config.autoMsptThreshold > 0.0
                && nowTick >= autoCooldownUntilServerTick
                && ServerMspt.averageMs(server) >= config.autoMsptThreshold) {
            beginAutoMsptSession(server);
            return;
        }

        if (config.scheduledProfileIntervalMinutes > 0) {
            long intervalMs = config.scheduledProfileIntervalMinutes * 60_000L;
            if (System.currentTimeMillis() - lastScheduledWallTimeMs >= intervalMs) {
                beginScheduledSession(server);
            }
        }
    }

    private static void beginAutoMsptSession(MinecraftServer server) {
        if (isRunning) {
            return;
        }
        sessionKind = SessionKind.AUTO_MSPT;
        sessionTickLimit = config.autoProfileDurationTicks;
        resetSessionState(true, null);
        isRunning = true;
        Messenger.m(
                server.getCommandSource().withSilent(),
                "l [SGUProfiler]",
                "w  ",
                "f 已因 MSPT≥",
                "w " + String.format(Locale.ROOT, "%.2f", config.autoMsptThreshold),
                "f  自动开始采样，持续 ",
                "w " + sessionTickLimit,
                "f  刻");
    }

    private static void beginScheduledSession(MinecraftServer server) {
        if (isRunning) {
            return;
        }
        lastScheduledWallTimeMs = System.currentTimeMillis();
        sessionKind = SessionKind.AUTO_SCHEDULED;
        sessionTickLimit = config.scheduledProfileDurationTicks;
        resetSessionState(true, null);
        isRunning = true;
        Messenger.m(
                server.getCommandSource().withSilent(),
                "l [SGUProfiler]",
                "w  ",
                "f 已按计划开始采样 ",
                "w " + sessionTickLimit,
                "f  刻");
    }

    private static void resetSessionState(boolean clearDims, Set<RegistryKey<World>> dims) {
        if (clearDims) {
            allowedDimensions = dims;
        }
        sessionCompletedTicks = 0;
        sampledTickCount = 0;
        currentTickSampled = false;
        lagSources.clear();
        botProfilingOnly = false;
    }

    public static Map<ProfileKey, LagSource> lagSources = new HashMap<>();

    public static void profile(Entity entity, LagType lagType, long timeCost) {
        if (botProfilingOnly && lagType != LagType.PLAYER_ACTION) {
            return;
        }
        if (!isRunning || !currentTickSampled) {
            return;
        }
        if (config.minProfileNanoseconds > 0 && timeCost < config.minProfileNanoseconds) {
            return;
        }
        RegistryKey<World> dim = entity.getWorld().getRegistryKey();
        if (allowedDimensions != null && !allowedDimensions.contains(dim)) {
            return;
        }
        ProfileKey pk = new ProfileKey(entity.getType(), dim);
        LagSource piece = new LagSource(pk, lagType, timeCost);
        lagSources.merge(pk, piece, (a, b) -> {
            a.merge(b);
            return a;
        });
    }

    private static long effectiveTickCount() {
        if (sampledTickCount > 0) {
            return sampledTickCount;
        }
        return Math.max(1L, sessionCompletedTicks);
    }

    private static double nsToAvgMsPerTick(long lagNs) {
        return (double) lagNs / 1_000_000.0D / (double) effectiveTickCount();
    }

    public static int start(CommandContext<ServerCommandSource> ctx) {
        return beginManual(ctx, null, false);
    }

    public static int startBot(CommandContext<ServerCommandSource> ctx) {
        return beginManual(ctx, null, true);
    }

    public static int startWithDims(CommandContext<ServerCommandSource> ctx, Set<RegistryKey<World>> dims) {
        return beginManual(ctx, dims, false);
    }

    public static int startBotWithDims(CommandContext<ServerCommandSource> ctx, Set<RegistryKey<World>> dims) {
        return beginManual(ctx, dims, true);
    }

    private static int beginManual(CommandContext<ServerCommandSource> ctx, Set<RegistryKey<World>> dimFilterOrNullForAll, boolean botMode) {
        ServerCommandSource src = ctx.getSource();
        if (isRunning) {
            Messenger.m(src, "y [SGUProfiler]", "w  ", "f 性能监测已在运行中");
            return 1;
        }
        sessionKind = SessionKind.MANUAL;
        sessionTickLimit = Integer.MAX_VALUE;
        resetSessionState(true, dimFilterOrNullForAll);
        botProfilingOnly = botMode;
        isRunning = true;
        String dimNote = dimensionHint(dimFilterOrNullForAll);
        if (botMode) {
            Messenger.m(
                    src,
                    "l [SGUProfiler]",
                    "w  ",
                    "f 性能监测已开始",
                    "w " + dimNote,
                    "f ；模式：仅 Carpet 假人「玩家操控」分项；再次执行停止指令可生成报告");
        } else {
            Messenger.m(
                    src,
                    "l [SGUProfiler]",
                    "w  ",
                    "f 性能监测已开始",
                    "w " + dimNote,
                    "f ；统计各游戏刻内实体相关耗时；再次执行停止指令可生成报告");
        }
        return 0;
    }

    private static String dimensionHint(Set<RegistryKey<World>> dims) {
        if (dims == null) {
            return "（维度：全部）";
        }
        StringBuilder sb = new StringBuilder("（维度：");
        boolean first = true;
        for (RegistryKey<World> k : dims) {
            if (!first) {
                sb.append("、");
            }
            first = false;
            sb.append(dimensionLabel(k));
        }
        sb.append("）");
        return sb.toString();
    }

    private static String dimensionLabel(RegistryKey<World> k) {
        if (k.equals(World.OVERWORLD)) {
            return "主世界";
        }
        if (k.equals(World.NETHER)) {
            return "下界";
        }
        if (k.equals(World.END)) {
            return "末地";
        }
        return k.getValue().toString();
    }

    public static int stop(CommandContext<ServerCommandSource> context) {
        return stopProfiling(context.getSource(), false);
    }

    public static int stopProfiling(ServerCommandSource src, boolean silentAuto) {
        if (!isRunning) {
            if (!silentAuto) {
                Messenger.m(
                        src,
                        "r [SGUProfiler]",
                        "w  ",
                        "f 当前没有在采样，请先执行 ",
                        "w /SGUProfiler profile start");
            }
            return 1;
        }

        boolean wasAutoMspt = sessionKind == SessionKind.AUTO_MSPT;
        boolean hadDimFilter = allowedDimensions != null;

        try {
            long ticks = effectiveTickCount();

            List<ProfileKey> byTotalDesc = lagSources.entrySet().stream()
                    .sorted(Map.Entry.<ProfileKey, LagSource>comparingByValue(
                            Comparator.comparingLong((LagSource ls) -> ls.sum()).reversed()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            List<ProfileKey> shownKeys =
                    byTotalDesc.stream().limit(REPORT_TOP_AGGREGATES).collect(Collectors.toList());

            List<String> web = new LinkedList<>();
            DoubleSummaryStatistics avgStats = new DoubleSummaryStatistics();
            for (ProfileKey pk : shownKeys) {
                LagSource agg = lagSources.get(pk);
                for (Map.Entry<LagType, Long> le : agg.lags.entrySet()) {
                    double avgMs = nsToAvgMsPerTick(le.getValue());
                    avgStats.accept(avgMs);
                    String dimPath = pk.dimension().getValue().getPath();
                    web.add(dimPath + "|" + pk.type().getName().getString() + "-" + le.getKey().name() + "-"
                            + String.format(Locale.ROOT, "%.3f", avgMs));
                }
            }

            double maxAvg = avgStats.getCount() > 0 ? avgStats.getMax() : 0.0;

            Messenger.m(src, "lb [SGUProfiler] 采样报告");
            if (sessionKind != SessionKind.MANUAL) {
                Messenger.m(src, "f （自动结束）");
            }
            boolean tickThrottled =
                    config.sampleEveryNTicks > 1
                            || config.tickSampleMode != TickSampleMode.STRIDE_ONLY
                            || sampledTickCount < sessionCompletedTicks;
            String tickLabel = tickThrottled ? "有效采样刻" : "游戏刻";
            int shownAgg = shownKeys.size();
            Messenger.m(
                    src,
                    "f 已统计 ",
                    "w " + ticks,
                    "f  " + tickLabel + " · 聚合 ",
                    "w " + byTotalDesc.size(),
                    "f  条实体-维度汇总 · 展示合计最高的 ",
                    "w " + shownAgg,
                    "f  条（最多 ",
                    "w " + REPORT_TOP_AGGREGATES,
                    "f  条，按 ms/Tick 降序）");
            if (tickThrottled) {
                Messenger.m(
                        src,
                        "f 采样策略 ",
                        "w " + config.tickSampleMode.name(),
                        "f  · 步长每 ",
                        "w " + config.sampleEveryNTicks,
                        "f  刻 · 重刻阈 ",
                        "w " + String.format(Locale.ROOT, "%.2f", config.heavyLastTickMsThreshold),
                        "f  ms · 实际经历刻 ",
                        "w " + sessionCompletedTicks);
            }
            Messenger.m(src, "w ");

            if (byTotalDesc.isEmpty()) {
                Messenger.m(
                        src,
                        "y [SGUProfiler]",
                        "w  ",
                        "f 本次无分项数据（表区以下为说明，仍会上报空快照）");
                if (hadDimFilter) {
                    Messenger.m(
                            src,
                            "f  · 维度采样：仅统计所选维度内实体；该维度若无加载区块/无实体则一直为空。",
                            "w ",
                            "f  · bot 模式仅统计 Carpet 假人的「玩家操控」项。",
                            "w ",
                            "f  · 检查 tickSampleMode / heavy 阈 / minProfileNanoseconds 是否过严。");
                } else {
                    Messenger.m(
                            src,
                            "f  · 请确认采样刻内有实体活动；heavy 模式或 minProfileNanoseconds 过高也可能无数据。");
                }
                Messenger.m(src, "w ");
            }

            for (ProfileKey pk : shownKeys) {
                LagSource agg = lagSources.get(pk);
                double entityTotalMs = nsToAvgMsPerTick(agg.sum());
                String entityName = pk.type().getName().getString();
                String dimLab = dimensionLabel(pk.dimension());

                List<Map.Entry<LagType, Long>> lines = agg.lags.entrySet().stream()
                        .sorted(Map.Entry.<LagType, Long>comparingByValue(Comparator.reverseOrder()))
                        .collect(Collectors.toList());

                String headStyle = carpetStyleForLoad(entityTotalMs, maxAvg);
                Messenger.m(
                        src,
                        headStyle + " ▶ ",
                        "w " + entityName,
                        "f  · ",
                        "w " + dimLab,
                        "f  （合计 ",
                        "w " + String.format(Locale.ROOT, "%.3f", entityTotalMs),
                        "f  ms/Tick · ",
                        "w " + lines.size(),
                        "f  条分项）");

                for (Map.Entry<LagType, Long> line : lines) {
                    LagType lt = line.getKey();
                    double avg = nsToAvgMsPerTick(line.getValue());
                    String st = carpetStyleForLoad(avg, maxAvg);
                    String label = lagTypeCn(lt);
                    String labelCol = label.length() >= 12 ? label : label + " ".repeat(Math.max(0, 12 - label.length()));
                    String num = String.format(Locale.ROOT, "%8.3f ms/Tick", avg);
                    Messenger.m(src, "f      ├ ", st + " " + labelCol, st + " " + num);
                }
                Messenger.m(src, "w ");
            }

            sendDataToFront(web, src);

            return 0;
        } finally {
            if (wasAutoMspt) {
                autoCooldownUntilServerTick = src.getServer().getTicks() + config.autoCooldownTicks;
            }
            lagSources.clear();
            isRunning = false;
            allowedDimensions = null;
            sessionKind = SessionKind.MANUAL;
            sessionTickLimit = Integer.MAX_VALUE;
            botProfilingOnly = false;
        }
    }

    private static String lagTypeCn(LagType t) {
        return switch (t) {
            case AI -> "AI";
            case PLAYER_ACTION -> "玩家操控";
            case TICK_MOVEMENT -> "位移 / 传送";
            case TICK -> "实体 Tick";
            case COLLISIONS -> "碰撞";
        };
    }

    private static String carpetStyleForLoad(double avgMs, double maxAvgMsInReport) {
        if (avgMs <= 0 || maxAvgMsInReport <= 0) {
            return "f";
        }
        return Messenger.heatmap_color(avgMs, maxAvgMsInReport);
    }

    private static String nextSnapshotId() {
        ZonedDateTime z = ZonedDateTime.now(ZoneId.systemDefault());
        String day = String.format(
                Locale.ROOT,
                "%04d%02d%02d",
                z.getYear(),
                z.getMonthValue(),
                z.getDayOfMonth());
        synchronized (SNAPSHOT_ID_LOCK) {
            if (!day.equals(lastSnapshotDay)) {
                lastSnapshotDay = day;
                snapshotCountThisDay = 0;
            }
            snapshotCountThisDay++;
            return day + "-" + snapshotCountThisDay;
        }
    }

    private static void sendDataToFront(List<String> list, ServerCommandSource source) {
        try {
            String snapshotId = nextSnapshotId();
            long now = Instant.now().toEpochMilli();
            String body = "{\"schema\":\"entity_lag_v1\",\"snapshotId\":\"" + snapshotId + "\",\"createdAtEpochMillis\":" + now
                    + ",\"lagData\":" + toJsonArray(list) + "}";
            byte[] secret = ingestSecretUtf8;
            String ts = String.valueOf(now);
            String toSign = ts + "\n" + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
            String sigHex = bytesToHex(sig);

            byte[] bodyUtf8 = body.getBytes(StandardCharsets.UTF_8);
            String host = config.ingestHost.strip();
            int port = config.ingestPort;
            String hostHeader = host + ":" + port;
            StringBuilder hdr = new StringBuilder(256);
            hdr.append("POST /api/v1/ingest HTTP/1.1\r\n");
            hdr.append("Host: ").append(hostHeader).append("\r\n");
            hdr.append("Content-Type: application/json; charset=UTF-8\r\n");
            hdr.append("Content-Length: ").append(bodyUtf8.length).append("\r\n");
            hdr.append("X-SGU-Timestamp-Ms: ").append(ts).append("\r\n");
            hdr.append("X-SGU-Signature: ").append(sigHex).append("\r\n");
            hdr.append("Connection: close\r\n");
            hdr.append("\r\n");
            byte[] headBytes = hdr.toString().getBytes(StandardCharsets.US_ASCII);

            try (Socket socket = new Socket()) {
                socket.setSoTimeout(45_000);
                socket.connect(new InetSocketAddress(host, port), 15_000);
                OutputStream out = socket.getOutputStream();
                out.write(headBytes);
                out.write(bodyUtf8);
                out.flush();
                byte[] responseBytes;
                try (InputStream in = socket.getInputStream()) {
                    responseBytes = in.readAllBytes();
                }
                int code = httpStatusFromResponse(responseBytes);
                if (code >= 200 && code < 300) {
                    Messenger.m(
                            source,
                            "l [SGUProfiler]",
                            "w  ",
                            "f 快照 ",
                            "lb " + snapshotId,
                            "f  已写入网页端 (HTTP ",
                            "w " + code,
                            "f )");
                } else if (code == 401) {
                    ingestUnauthorizedHint(source, httpResponseBodyUtf8(responseBytes));
                } else if (code <= 0) {
                    Messenger.m(
                            source,
                            "r [SGUProfiler]",
                            "w  ",
                            "f 未收到有效 HTTP 响应，请确认后端 ",
                            "w " + config.ingestHost + ":" + config.ingestPort,
                            "f  已启动");
                } else {
                    Messenger.m(
                            source,
                            "r [SGUProfiler]",
                            "w  ",
                            "f 上报被拒绝 HTTP ",
                            "r " + code,
                            "f  （可查后端日志 / 密钥 SGUPROF_INGEST_SECRET）");
                }
            }
        } catch (Exception e) {
            Messenger.m(source, "r [SGUProfiler]", "w  ", "f 上报连接异常 ", "r " + e.getClass().getSimpleName());
        }
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

    private static void ingestUnauthorizedHint(ServerCommandSource source, String body) {
        if (body.contains("timestamp_out_of_window")) {
            Messenger.m(
                    source,
                    "r [SGUProfiler]",
                    "w  ",
                    "f 服务端判定时间戳超出允许范围，请同步系统时间后重试");
            return;
        }
        if (body.contains("bad_signature")) {
            Messenger.m(
                    source,
                    "r [SGUProfiler]",
                    "w  ",
                    "f 上报验签失败（密钥与后端 SGUPROF_INGEST_SECRET 不一致）");
            Messenger.m(
                    source,
                    "f 请在 Fabric 配置目录编辑 ",
                    "w config/sguprofiler.json",
                    "f  ，将 ",
                    "w ingestSecret",
                    "f  设为与后端 .env 相同（UTF-8），保存后重启服务端；也可用 ",
                    "w -Dsguprof.ingestSecret=…",
                    "f  或同名环境变量覆盖");
            Messenger.m(source, "f 模组与 uvicorn 日志里的 ", "w utf8_bytes", "f  必须相同");
            return;
        }
        Messenger.m(source, "r [SGUProfiler]", "w  ", "f HTTP 401，请检查 ingest 密钥与时间");
    }

    private static int httpStatusFromResponse(byte[] response) {
        if (response == null || response.length < 12) {
            return 0;
        }
        try {
            int end = 0;
            while (end < response.length && response[end] != '\n') {
                end++;
            }
            String line = new String(response, 0, end, StandardCharsets.US_ASCII).trim();
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                return Integer.parseInt(parts[1]);
            }
        } catch (Exception ignored) {
            /* ignore */
        }
        return 0;
    }

    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(list.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
