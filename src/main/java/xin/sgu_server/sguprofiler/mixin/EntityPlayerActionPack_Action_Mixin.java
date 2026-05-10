package xin.sgu_server.sguprofiler.mixin;

import carpet.helpers.EntityPlayerActionPack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import xin.sgu_server.sguprofiler.profiler.ActionPackProfileBridge;
import xin.sgu_server.sguprofiler.profiler.LagType;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

@Mixin(targets = "carpet.helpers.EntityPlayerActionPack$Action")
public class EntityPlayerActionPack_Action_Mixin {

    @WrapMethod(method = "tick")
    private Boolean sguprofiler$wrapTick(
            EntityPlayerActionPack pack,
            EntityPlayerActionPack.ActionType type,
            Operation<Boolean> original) {
        if (!SGUProfiler.isRunning) {
            return original.call(pack, type);
        }
        long start = System.nanoTime();
        Boolean result = original.call(pack, type);
        long dt = System.nanoTime() - start;
        ServerPlayerEntity player = ((EntityPlayerActionPackAccessor) pack).sguprofiler$player();
        LagType lagType =
                switch (type) {
                    case ATTACK -> LagType.PLAYER_ACTION_ATTACK;
                    case USE -> LagType.PLAYER_ACTION_USE;
                    default -> LagType.PLAYER_ACTION;
                };
        SGUProfiler.profile(player, lagType, dt);
        ActionPackProfileBridge.addActionNs(dt);
        return result;
    }

    @WrapMethod(method = "retry")
    private void sguprofiler$wrapRetry(
            EntityPlayerActionPack pack,
            EntityPlayerActionPack.ActionType type,
            Operation<Void> original) {
        if (!SGUProfiler.isRunning) {
            original.call(pack, type);
            return;
        }
        long start = System.nanoTime();
        original.call(pack, type);
        long dt = System.nanoTime() - start;
        ServerPlayerEntity player = ((EntityPlayerActionPackAccessor) pack).sguprofiler$player();
        LagType lagType =
                switch (type) {
                    case ATTACK -> LagType.PLAYER_ACTION_ATTACK;
                    case USE -> LagType.PLAYER_ACTION_USE;
                    default -> LagType.PLAYER_ACTION;
                };
        SGUProfiler.profile(player, lagType, dt);
        ActionPackProfileBridge.addActionNs(dt);
    }
}
