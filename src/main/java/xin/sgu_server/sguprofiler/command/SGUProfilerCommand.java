package xin.sgu_server.sguprofiler.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.world.World;
import xin.sgu_server.sguprofiler.CommandAccess;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

import java.util.Set;

public class SGUProfilerCommand {
    public static void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> startTree =
                CommandManager.literal("start")
                        .executes(SGUProfiler::start)
                        .then(
                                CommandManager.literal("bot")
                                        .executes(SGUProfiler::startBot)
                                        .then(
                                                CommandManager.literal("overworld")
                                                        .executes(
                                                                c -> SGUProfiler.startBotWithDims(c, Set.of(World.OVERWORLD))))
                                        .then(
                                                CommandManager.literal("nether")
                                                        .executes(
                                                                c -> SGUProfiler.startBotWithDims(c, Set.of(World.NETHER))))
                                        .then(
                                                CommandManager.literal("end")
                                                        .executes(
                                                                c -> SGUProfiler.startBotWithDims(c, Set.of(World.END))))
                                        .then(CommandManager.literal("all").executes(SGUProfiler::startBot)))
                        .then(
                                CommandManager.literal("overworld")
                                        .executes(c -> SGUProfiler.startWithDims(c, Set.of(World.OVERWORLD))))
                        .then(
                                CommandManager.literal("nether")
                                        .executes(c -> SGUProfiler.startWithDims(c, Set.of(World.NETHER))))
                        .then(
                                CommandManager.literal("end")
                                        .executes(c -> SGUProfiler.startWithDims(c, Set.of(World.END))))
                        .then(CommandManager.literal("all").executes(SGUProfiler::start));

        LiteralArgumentBuilder<ServerCommandSource> profile =
                CommandManager.literal("profile")
                        .then(startTree)
                        .then(CommandManager.literal("stop").executes(SGUProfiler::stop));

        LiteralArgumentBuilder<ServerCommandSource> root =
                CommandManager.literal("SGUProfiler")
                        .requires(CommandAccess::canUseProfilerCommands)
                        .then(profile);

        dispatcher.register(root);
    }
}
