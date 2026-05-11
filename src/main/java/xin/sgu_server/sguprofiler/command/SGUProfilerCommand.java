package xin.sgu_server.sguprofiler.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.world.World;
import xin.sgu_server.sguprofiler.CommandAccess;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

import java.util.Set;

public final class SGUProfilerCommand {
    private SGUProfilerCommand() {
    }

    public static void registerCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
        // 每条根字面量单独建树：Brigadier 的 builder 不能安全地挂到多个父节点上
        for (String rootName : new String[] {"SGUProfiler", "sguprofiler"}) {
            dispatcher.register(registerRoot(rootName));
        }
    }

    private static LiteralArgumentBuilder<ServerCommandSource> registerRoot(String rootName) {
        return CommandManager.literal(rootName)
                .requires(CommandAccess::canUseProfilerCommands)
                .then(profileBranch())
                .then(whitelistBranch());
    }

    private static LiteralArgumentBuilder<ServerCommandSource> startBranch() {
        return CommandManager.literal("start")
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
                                                .executes(c -> SGUProfiler.startBotWithDims(c, Set.of(World.NETHER))))
                                .then(
                                        CommandManager.literal("end")
                                                .executes(c -> SGUProfiler.startBotWithDims(c, Set.of(World.END))))
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
    }

    private static LiteralArgumentBuilder<ServerCommandSource> profileBranch() {
        return CommandManager.literal("profile")
                .then(startBranch())
                .then(CommandManager.literal("stop").executes(SGUProfiler::stop));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> whitelistBranch() {
        return CommandManager.literal("whitelist")
                .requires(CommandAccess::canManageProfilerWhitelist)
                .then(
                        CommandManager.literal("add")
                                .then(
                                        CommandManager.argument("target", EntityArgumentType.player())
                                                .executes(ProfilerWhitelistCommands::add)))
                .then(
                        CommandManager.literal("remove")
                                .then(
                                        CommandManager.argument("target", EntityArgumentType.player())
                                                .executes(ProfilerWhitelistCommands::remove)))
                .then(CommandManager.literal("list").executes(ProfilerWhitelistCommands::list))
                .then(CommandManager.literal("clear").executes(ProfilerWhitelistCommands::clear));
    }
}
