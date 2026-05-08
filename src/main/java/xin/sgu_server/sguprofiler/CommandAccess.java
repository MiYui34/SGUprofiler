package xin.sgu_server.sguprofiler;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/** 游戏内命令：正版 OP，或网站白名单 UUID；非玩家来源需权限等级 ≥4（控制台等）。 */
public final class CommandAccess {
    private CommandAccess() {
    }

    public static boolean canUseProfilerCommands(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            if (player.getServer().getPlayerManager().isOperator(player.getGameProfile())) {
                return true;
            }
            return ProfilerAllowlistCache.contains(player.getUuid());
        }
        return source.hasPermissionLevel(4);
    }
}
