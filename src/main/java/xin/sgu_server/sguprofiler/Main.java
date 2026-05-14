package xin.sgu_server.sguprofiler;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.sgu_server.sguprofiler.command.SGUProfilerCommand;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

import java.nio.file.Path;

public class Main implements ModInitializer {
    public static final String MOD_ID = "SGUProfiler";
    public static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    @Override
    public void onInitialize() {
        LOGGER.info("[SGUProfiler] Loading SGUProfiler...");

        FabricLoader fl = FabricLoader.getInstance();
        Path configDir = fl.getConfigDir();
        SguprofilerConfig.ensureConfigFile(configDir, LOGGER);
        SguprofilerConfig profCfg = SguprofilerConfig.load(configDir, LOGGER);
        SGUProfiler.applyConfig(profCfg);
        ProfilerCommandWhitelist.init(configDir, LOGGER);

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {
                    SGUProfilerCommand.registerCommand(dispatcher);
                    LOGGER.info("[SGUProfiler] 已注册命令：/SGUProfiler …（玩家需 OP 或命令白名单）");
                });

        ServerLifecycleEvents.SERVER_STARTED.register(SGUProfiler::onServerStarted);
        ServerTickEvents.START_SERVER_TICK.register(SGUProfiler::onStartServerTick);
        ServerTickEvents.END_SERVER_TICK.register(SGUProfiler::onEndServerTick);
    }
}
