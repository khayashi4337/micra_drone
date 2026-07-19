package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.drone.DroneEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Custom look for {@link DroneEntity}, rebuilt from scratch against the three commissioned reference
 * renders ({@code explain/micar_drone_logo.png} + back/bottom views). Unlike earlier attempts, this
 * version was authored through a software previewer (scratchpad {@code drone_pipeline.py}) that
 * renders the exact same box/UV definitions to PNG - every part below was visually verified against
 * the references from the front, back, side, and bottom before being transcribed here 1:1. The
 * structural hierarchy now matches the artwork: body -> side pods -> vertical masts rising from the
 * pods -> propellers on top (the "arms raised" silhouette), not horizontal arms with rotors on the
 * ends. The downward jet in the artwork is done with particles, not geometry - see DroneEntity#tick.
 */
public class DroneModel extends HierarchicalModel<DroneEntity> {
    private final ModelPart root;
    private final ModelPart propRight;
    private final ModelPart propLeft;

    public DroneModel(ModelPart root) {
        this.root = root.getChild("root");
        ModelPart body = this.root.getChild("body");
        this.propRight = body.getChild("pod_right").getChild("mast_right").getChild("prop_right");
        this.propLeft = body.getChild("pod_left").getChild("mast_left").getChild("prop_left");
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot().addOrReplaceChild("root", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Main hull: 10x10x10 cube. Face screen (front), vent panel (back), and belly detail are all
        // painted in the texture - see drone_pipeline.py's paint_body().
        PartDefinition body = root.addOrReplaceChild(
                "body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-5.0F, -10.0F, -5.0F, 10.0F, 10.0F, 10.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -4.0F, 0.0F));

        body.addOrReplaceChild(
                "hatch",
                CubeListBuilder.create().texOffs(0, 20).addBox(-3.0F, -1.0F, -3.0F, 6.0F, 1.0F, 6.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -10.0F, 0.0F));

        // Chin light module hangs off the hull's lower front edge, half below it (per the logo).
        body.addOrReplaceChild(
                "chin",
                CubeListBuilder.create().texOffs(26, 20).addBox(-2.0F, 0.0F, -1.0F, 4.0F, 4.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -1.5F, -5.0F));

        // Thruster nozzle under the hull; the jet itself is particles (DroneEntity#tick).
        body.addOrReplaceChild(
                "nozzle",
                CubeListBuilder.create().texOffs(0, 27).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 2.0F, 4.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        // Right-side pod -> vertical mast -> propeller. Left side mirrors the offsets and reuses the
        // same texture slots (the design is symmetric).
        PartDefinition podRight = body.addOrReplaceChild(
                "pod_right",
                CubeListBuilder.create().texOffs(16, 27).addBox(-4.0F, -2.0F, -2.0F, 4.0F, 4.0F, 4.0F, CubeDeformation.NONE),
                PartPose.offset(-5.0F, -6.0F, 0.0F));
        PartDefinition mastRight = podRight.addOrReplaceChild(
                "mast_right",
                CubeListBuilder.create()
                        .texOffs(33, 27).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, CubeDeformation.NONE)
                        .texOffs(42, 27).addBox(-0.5F, -5.0F, -0.5F, 1.0F, 3.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offset(-2.0F, -2.0F, 0.0F));
        mastRight.addOrReplaceChild(
                "prop_right",
                CubeListBuilder.create()
                        .texOffs(0, 36).addBox(-5.0F, -1.0F, -1.0F, 10.0F, 1.0F, 2.0F, CubeDeformation.NONE)
                        .texOffs(0, 40).addBox(-1.0F, -1.0F, -5.0F, 2.0F, 1.0F, 10.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -5.0F, 0.0F));

        PartDefinition podLeft = body.addOrReplaceChild(
                "pod_left",
                CubeListBuilder.create().texOffs(16, 27).addBox(0.0F, -2.0F, -2.0F, 4.0F, 4.0F, 4.0F, CubeDeformation.NONE),
                PartPose.offset(5.0F, -6.0F, 0.0F));
        PartDefinition mastLeft = podLeft.addOrReplaceChild(
                "mast_left",
                CubeListBuilder.create()
                        .texOffs(33, 27).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, CubeDeformation.NONE)
                        .texOffs(42, 27).addBox(-0.5F, -5.0F, -0.5F, 1.0F, 3.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offset(2.0F, -2.0F, 0.0F));
        mastLeft.addOrReplaceChild(
                "prop_left",
                CubeListBuilder.create()
                        .texOffs(0, 36).addBox(-5.0F, -1.0F, -1.0F, 10.0F, 1.0F, 2.0F, CubeDeformation.NONE)
                        .texOffs(0, 40).addBox(-1.0F, -1.0F, -5.0F, 2.0F, 1.0F, 10.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -5.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(DroneEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);
        this.root.y += Mth.cos(ageInTicks * 0.1F) * 0.6F;
        this.propRight.yRot = ageInTicks * 3.5F;
        this.propLeft.yRot = -ageInTicks * 3.5F;
    }
}
