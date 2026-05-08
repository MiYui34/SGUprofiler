package xin.sgu_server.sguprofiler.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import xin.sgu_server.sguprofiler.profiler.LagType;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

@Mixin(MobEntity.class)
public class MobEntity_Mixin {
    @WrapMethod(
            method = "tickNewAi"
    )
    public void tick_AI(Operation<Void> original) {
        if (SGUProfiler.isRunning) {
            long startTime = System.nanoTime();
            original.call();
            SGUProfiler.profile((MobEntity) (Object) this, LagType.AI, System.nanoTime() - startTime);
        }
        else {
            original.call();
        }
    }
}
