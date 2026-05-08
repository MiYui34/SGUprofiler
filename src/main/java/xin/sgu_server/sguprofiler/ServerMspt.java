package xin.sgu_server.sguprofiler;

import net.minecraft.server.MinecraftServer;

/** 服务端平滑 MSPT（毫秒），用于自动触发阈值。 */
public final class ServerMspt {
    private ServerMspt() {
    }

    public static double averageMs(MinecraftServer server) {
        return server.getAverageTickTime();
    }
}
