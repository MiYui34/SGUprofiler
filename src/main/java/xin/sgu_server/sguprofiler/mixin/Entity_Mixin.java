package xin.sgu_server.sguprofiler.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xin.sgu_server.sguprofiler.profiler.LagType;
import xin.sgu_server.sguprofiler.profiler.SGUProfiler;

@Mixin(Entity.class)
public class Entity_Mixin {
    @Inject(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;adjustMovementForSneaking(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/entity/MovementType;)Lnet/minecraft/util/math/Vec3d;")
    )
    public void move_Inject(MovementType type, Vec3d movement, CallbackInfo ci, @Share("start") LocalLongRef ref) {
        if (SGUProfiler.isRunning) {
            ref.set(System.nanoTime());
        }
    }

    @Inject(
            method = "move",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiler/Profiler;pop()V", ordinal = 0)
    )
    public void move(MovementType type, Vec3d movement, CallbackInfo ci, @Share("start") LocalLongRef ref) {
        if (SGUProfiler.isRunning) {
            SGUProfiler.profile((Entity) (Object) this, LagType.COLLISIONS, System.nanoTime() - ref.get());
        }
    }
}
