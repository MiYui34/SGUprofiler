package xin.sgu_server.sguprofiler;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.sgu_server.sguprofiler.AllowlistPuller;
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
        IngestSecretLoader.ensureConfigTemplate(configDir, LOGGER);
        byte[] ingestUtf8 = IngestSecretLoader.resolveUtf8(configDir, LOGGER);
        SGUProfiler.setIngestSecretUtf8(ingestUtf8);
        SguprofilerConfig profCfg = SguprofilerConfig.load(configDir, LOGGER);
        SGUProfiler.applyConfig(profCfg);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SGUProfiler.onServerStarted(server);
            AllowlistPuller.onServerStarted(server);
        });
        ServerTickEvents.START_SERVER_TICK.register(SGUProfiler::onStartServerTick);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            SGUProfiler.onEndServerTick(server);
            AllowlistPuller.onEndServerTick(server);
        });
    }
}
