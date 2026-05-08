package xin.sgu_server.sguprofiler.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import xin.sgu_server.sguprofiler.profiler.LagType;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

@Mixin(ServerWorld.class)
public class ServerWorld_Mixin {
    @WrapMethod(
            method = "tickEntity"
    )
    public void tick_Entity(Entity entity, Operation<Void> original) {
        if (SGUProfiler.isRunning) {
            long startTime = System.nanoTime();
            original.call(entity);
            SGUProfiler.profile(entity, LagType.TICK, System.nanoTime() - startTime);
        }
        else {
            original.call(entity);
        }
    }
}
