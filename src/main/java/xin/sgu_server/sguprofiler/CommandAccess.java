package xin.sgu_server.sguprofiler;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

/** 游戏内命令：OP、白名单玩家；非玩家来源按配置 {@code permissionFallbackLevel}。 */
public final class CommandAccess {
    private CommandAccess() {
    }

    /** 仅 OP 或控制台（权限等级 ≥4）可管理白名单子命令。 */
    public static boolean canManageProfilerWhitelist(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            return McCompat.isOperator(McCompat.serverOf(player).getPlayerManager(), player);
        }
        return McCompat.hasCommandPermissionLevel(source, 4);
    }

    public static boolean canUseProfilerCommands(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            if (McCompat.isOperator(McCompat.serverOf(player).getPlayerManager(), player)) {
                return true;
            }
            return ProfilerCommandWhitelist.contains(player.getUuid());
        }
        return McCompat.hasCommandPermissionLevel(source, SGUProfiler.permissionFallbackLevel());
    }
}
