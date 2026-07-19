package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.MicraDroneClient;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Renders {@link DroneEntity} with {@link DroneModel} instead of the reused AllayRenderer/AllayModel. */
public class DroneRenderer extends MobRenderer<DroneEntity, DroneModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "textures/entity/drone.png");

    public DroneRenderer(EntityRendererProvider.Context context) {
        super(context, new DroneModel(context.bakeLayer(MicraDroneClient.DRONE_MODEL_LAYER)), 0.4F);
    }

    @Override
    public ResourceLocation getTextureLocation(DroneEntity entity) {
        return TEXTURE;
    }

    @Override
    protected int getBlockLightLevel(DroneEntity entity, BlockPos pos) {
        return 15;
    }
}
