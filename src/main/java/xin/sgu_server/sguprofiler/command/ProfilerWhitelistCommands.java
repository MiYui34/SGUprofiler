package xin.sgu_server.sguprofiler.command;

import carpet.utils.Messenger;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import xin.sgu_server.sguprofiler.McCompat;
import xin.sgu_server.sguprofiler.ProfilerCommandWhitelist;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ProfilerWhitelistCommands {
    private ProfilerWhitelistCommands() {
    }

    public static int add(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
        ServerCommandSource src = ctx.getSource();
        if (ProfilerCommandWhitelist.add(target.getUuid())) {
            Messenger.m(
                    src,
                    "l [SGUProfiler]",
                    "w 白名单+ ",
                    "w " + target.getName().getString());
        } else {
            Messenger.m(
                    src,
                    "y [SGUProfiler]",
                    "w 已在白名单 ",
                    "w " + target.getName().getString());
        }
        return 1;
    }

    public static int remove(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
        ServerCommandSource src = ctx.getSource();
        if (ProfilerCommandWhitelist.remove(target.getUuid())) {
            Messenger.m(
                    src,
                    "l [SGUProfiler]",
                    "w 白名单- ",
                    "w " + target.getName().getString());
        } else {
            Messenger.m(
                    src,
                    "y [SGUProfiler]",
                    "w 不在白名单 ",
                    "w " + target.getName().getString());
        }
        return 1;
    }

    public static int list(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        List<UUID> uuids = ProfilerCommandWhitelist.snapshotSorted();
        if (uuids.isEmpty()) {
            Messenger.m(src, "y [SGUProfiler]", "w 白名单为空（OP 不受限）");
            return 0;
        }
        MinecraftServer server = src.getServer();
        String line =
                uuids.stream().map(u -> profileLabel(server, u)).collect(Collectors.joining(", "));
        Messenger.m(src, "l [SGUProfiler]", "w ", "f " + line);
        return uuids.size();
    }

    public static int clear(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource src = ctx.getSource();
        if (ProfilerCommandWhitelist.size() == 0) {
            throw new SimpleCommandExceptionType(Text.literal("[SGUProfiler] 白名单已空")).create();
        }
        int n = ProfilerCommandWhitelist.clear();
        Messenger.m(src, "l [SGUProfiler]", "w 已清空白名单 ", "w " + n, "f 人");
        return n;
    }

    private static String profileLabel(MinecraftServer server, UUID u) {
        return McCompat.findProfileByUuid(server, u).map(McCompat::gameProfileName).orElseGet(() -> u.toString());
    }
}
