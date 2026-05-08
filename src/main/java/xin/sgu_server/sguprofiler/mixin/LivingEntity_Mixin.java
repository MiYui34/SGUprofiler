package xin.sgu_server.sguprofiler.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import xin.sgu_server.sguprofiler.profiler.LagType;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

@Mixin(LivingEntity.class)
public class LivingEntity_Mixin {
    @WrapMethod(
            method = "tickMovement"
    )
    public void tick_Movement(Operation<Void> original) {
        if (SGUProfiler.isRunning) {
            long startTime = System.nanoTime();
            original.call();
            SGUProfiler.profile((LivingEntity) (Object) this, LagType.TICK_MOVEMENT, System.nanoTime() - startTime);
        }
        else {
            original.call();
        }
    }
}
