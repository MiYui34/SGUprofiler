package xin.sgu_server.sguprofiler.profiler;

import carpet.utils.Messenger;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.world.World;
import xin.sgu_server.sguprofiler.McCompat;
import xin.sgu_server.sguprofiler.ServerMspt;
import xin.sgu_server.sguprofiler.SguprofilerConfig;

import java.util.*;
import java.util.stream.Collectors;

public class SGUProfiler {
    private static final int REPORT_TOP_AGGREGATES = 10;

    private static SguprofilerConfig config = SguprofilerConfig.defaults();

    public static void applyConfig(SguprofilerConfig c) {
        if (c != null) {
            config = c;
        }
    }

    public static SguprofilerConfig config() {
        return config;
    }

    public static int permissionFallbackLevel() {
        return config.permissionFallbackLevel;
    }

    /** {@code true} 时仅统计 Carpet {@code EntityPlayerActionPack} 相关分项（Attack / Use / 其余操控）。 */
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

    /** bot 模式下纳入统计的 Carpet {@code EntityPlayerActionPack} 分项。 */
    private static boolean countsTowardBotActionPack(LagType lagType) {
        return lagType == LagType.PLAYER_ACTION
                || lagType == LagType.PLAYER_ACTION_ATTACK
                || lagType == LagType.PLAYER_ACTION_USE;
    }

    public static void profile(Entity entity, LagType lagType, long timeCost) {
        if (McCompat.worldOf(entity).isClient()) {
            return;
        }
        if (botProfilingOnly && !countsTowardBotActionPack(lagType)) {
            return;
        }
        if (!isRunning || !currentTickSampled) {
            return;
        }
        if (config.minProfileNanoseconds > 0 && timeCost < config.minProfileNanoseconds) {
            return;
        }
        RegistryKey<World> dim = McCompat.worldOf(entity).getRegistryKey();
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

    /**
     * 实体分项累计纳秒 ÷ 有效采样刻数 → 每采样刻平均毫秒（表内「ms/采样刻」）。
     * 与 F3 / {@link ServerMspt} 的整刻 wall MSPT 不同，不可直接对比或把多行相加。
     */
    private static double nsToAvgProfileMsPerSampledTick(long lagNs) {
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
                    "f ；模式：仅 Carpet 假人操控（Attack / Use / 其它）；再次执行停止指令可生成报告");
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

            List<ProfileKey> byTotalDesc = lagSources.entrySet().stream()
                    .sorted(Map.Entry.<ProfileKey, LagSource>comparingByValue(
                            Comparator.comparingLong((LagSource ls) -> ls.sum()).reversed()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            List<ProfileKey> shownKeys =
                    byTotalDesc.stream().limit(REPORT_TOP_AGGREGATES).collect(Collectors.toList());

            DoubleSummaryStatistics avgStats = new DoubleSummaryStatistics();
            for (ProfileKey pk : shownKeys) {
                LagSource agg = lagSources.get(pk);
                for (Map.Entry<LagType, Long> le : agg.lags.entrySet()) {
                    avgStats.accept(nsToAvgProfileMsPerSampledTick(le.getValue()));
                }
            }

            double maxAvg = avgStats.getCount() > 0 ? avgStats.getMax() : 0.0;

            Messenger.m(src, "lb [SGUProfiler] 采样报告");
            if (sessionKind != SessionKind.MANUAL) {
                Messenger.m(src, "f （自动结束）");
            }
            int shownAgg = shownKeys.size();
            Messenger.m(
                    src,
                    "f 经历 ",
                    "w " + sessionCompletedTicks,
                    "f  游戏刻 · 聚合 ",
                    "w " + byTotalDesc.size(),
                    "f  条实体-维度汇总 · 展示合计最高的 ",
                    "w " + shownAgg,
                    "f  条（最多 ",
                    "w " + REPORT_TOP_AGGREGATES,
                    "f  条，按 ms/采样刻均值降序）");
            Messenger.m(src, "w ");
            Messenger.m(
                    src,
                    "y [SGUProfiler]",
                    "w 说明：",
                    "f 表中为「实体分项」在有效采样刻上的 ms/刻均值，不是 F3/整服 MSPT；多行不可相加对比 MSPT。");
            if (sampledTickCount > 0 && sampledTickCount < sessionCompletedTicks) {
                Messenger.m(
                        src,
                        "y [SGUProfiler]",
                        "w 采样：",
                        "w " + sampledTickCount,
                        "f 有效采样刻 / ",
                        "w " + sessionCompletedTicks,
                        "f 经历刻");
            }
            Messenger.m(src, "w ");

            if (byTotalDesc.isEmpty()) {
                Messenger.m(
                        src,
                        "y [SGUProfiler]",
                        "w  ",
                        "f 本次无分项数据（以下为可能原因）");
                if (hadDimFilter) {
                    Messenger.m(
                            src,
                            "f  · 维度采样：仅统计所选维度内实体；该维度若无加载区块/无实体则一直为空。",
                            "w ",
                            "f  · bot 模式仅统计 Carpet 假人 ActionPack（Attack / Use / 其它操控）。",
                            "w ",
                            "f  · 检查 minProfileNanoseconds 等阈值是否过严。");
                } else {
                    Messenger.m(
                            src,
                            "f  · 请确认采样刻内有实体活动；minProfileNanoseconds 过高等也可能无数据。");
                }
                Messenger.m(src, "w ");
            }

            for (ProfileKey pk : shownKeys) {
                LagSource agg = lagSources.get(pk);
                double entityTotalMs = nsToAvgProfileMsPerSampledTick(agg.sum());
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
                        "f  ms/采样刻 · ",
                        "w " + lines.size(),
                        "f  条分项）");

                for (Map.Entry<LagType, Long> line : lines) {
                    LagType lt = line.getKey();
                    double avg = nsToAvgProfileMsPerSampledTick(line.getValue());
                    String st = carpetStyleForLoad(avg, maxAvg);
                    String label = lagTypeCn(lt);
                    String labelCol = label.length() >= 12 ? label : label + " ".repeat(Math.max(0, 12 - label.length()));
                    String num = String.format(Locale.ROOT, "%8.3f ms/采样刻", avg);
                    Messenger.m(src, "f      ├ ", st + " " + labelCol, st + " " + num);
                }
                Messenger.m(src, "w ");
            }

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
            case PLAYER_ACTION -> "其它操控";
            case PLAYER_ACTION_ATTACK -> "攻击";
            case PLAYER_ACTION_USE -> "使用";
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

}
