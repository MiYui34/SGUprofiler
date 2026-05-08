package xin.sgu_server.sguprofiler.mixin;

import carpet.helpers.EntityPlayerActionPack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
            long startTime = System.nanoTime();
            original.call();
            SGUProfiler.profile(this.player, LagType.PLAYER_ACTION, System.nanoTime() - startTime);
        } else  {
            original.call();
        }
    }
}
