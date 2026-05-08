package xin.sgu_server.sguprofiler.profiler;

import net.minecraft.entity.EntityType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** 按实体类型与维度聚合，用于分维度采样报告。 */
public record ProfileKey(EntityType<?> type, RegistryKey<World> dimension) {
}
