package xin.sgu_server.sguprofiler.mixin;

import carpet.helpers.EntityPlayerActionPack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import xin.sgu_server.sguprofiler.profiler.ActionPackProfileBridge;
import xin.sgu_server.sguprofiler.profiler.LagType;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

@Mixin(EntityPlayerActionPack.class)
public class EntityPlayerActionPack_Mixin {
    @Shadow
    @Final
    private ServerPlayerEntity player;

    @WrapMethod(
            method = "onUpdate"
    )
    public void onUpdate(Operation<Void> original) {
        if (SGUProfiler.isRunning) {
            ActionPackProfileBridge.clear();
            long startTime = System.nanoTime();
            original.call();
            long total = System.nanoTime() - startTime;
            long inActions = ActionPackProfileBridge.takeAccumulatedNs();
            long rest = Math.max(0L, total - inActions);
            if (rest > 0) {
                SGUProfiler.profile(this.player, LagType.PLAYER_ACTION, rest);
            }
        } else {
            original.call();
        }
    }
}
