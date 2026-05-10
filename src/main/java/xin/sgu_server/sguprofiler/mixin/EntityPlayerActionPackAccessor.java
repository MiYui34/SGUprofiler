package xin.sgu_server.sguprofiler.mixin;

import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityPlayerActionPack.class)
public interface EntityPlayerActionPackAccessor {
    @Accessor("player")
    ServerPlayerEntity sguprofiler$player();
}
