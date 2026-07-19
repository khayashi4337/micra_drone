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
 * Custom look for {@link DroneEntity} (a plain Allay subclass server-side - see that class - reused
 * only for its behavior, not its visuals): a small hovering cube-headed robot with two side-mounted
 * rotors, designed to match the logo artwork the player commissioned (see
 * {@code explain/micar_drone_logo.png} and the back/bottom reference renders in the same folder).
 * Geometry follows {@code AllayModel}'s coordinate conventions closely (same root offset, same overall
 * scale) since {@link DroneEntity} keeps Allay's exact hitbox - this is a from-scratch model, not a
 * retexture, so there's no in-engine visual feedback loop while authoring it; expect the proportions
 * to need a manual touch-up pass once seen in-game.
 */
public class DroneModel extends HierarchicalModel<DroneEntity> {
    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightRotor;
    private final ModelPart leftRotor;

    public DroneModel(ModelPart root) {
        this.root = root.getChild("root");
        this.body = this.root.getChild("body");
        this.rightArm = this.body.getChild("right_arm_light").getChild("right_arm");
        this.leftArm = this.body.getChild("left_arm_light").getChild("left_arm");
        this.rightRotor = this.rightArm.getChild("right_rotor");
        this.leftRotor = this.leftArm.getChild("left_rotor");
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot().addOrReplaceChild("root", CubeListBuilder.create(), PartPose.offset(0.0F, 23.5F, 0.0F));

        PartDefinition body = root.addOrReplaceChild(
                "body",
                CubeListBuilder.create().texOffs(0, 0).addBox(-3.5F, -7.0F, -3.5F, 7.0F, 7.0F, 7.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -2.5F, 0.0F));

        body.addOrReplaceChild(
                "chin_light",
                CubeListBuilder.create().texOffs(0, 44).addBox(-1.0F, 0.0F, -3.9F, 2.0F, 1.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, 0.0F, 0.0F));

        body.addOrReplaceChild(
                "top_hatch",
                CubeListBuilder.create().texOffs(0, 48).addBox(-1.5F, -0.5F, -1.0F, 3.0F, 1.0F, 2.0F, CubeDeformation.NONE),
                PartPose.offset(0.0F, -7.0F, 0.0F));

        // Round 2 fix: every reference image shows a small glowing "mount light" box between the body
        // and the dark mast - the previous version went straight from body to mast, missing it.
        // Follow-up fix (from an in-game screenshot + direct feedback): the whole arm assembly was
        // pure translation with no rotation anywhere, so it could only ever come out flat/horizontal -
        // never the raised "banzai" angle every reference image shows. This is a real joint now: a
        // single zRot here tilts this mount plus everything childed under it (arm, rotor) together as
        // one rigid unit, the same way a shoulder joint would. Mounted higher on the body too (closer
        // to the top edge, not mid-height) to match the reference silhouette.
        PartDefinition rightArmLight = body.addOrReplaceChild(
                "right_arm_light",
                CubeListBuilder.create().texOffs(0, 51).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, CubeDeformation.NONE),
                PartPose.offsetAndRotation(-3.5F, -5.5F, 0.0F, 0.0F, 0.0F, 0.45F));
        PartDefinition rightArm = rightArmLight.addOrReplaceChild(
                "right_arm",
                CubeListBuilder.create().texOffs(0, 16).addBox(-4.0F, -0.5F, -0.5F, 4.0F, 1.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offset(-1.0F, 0.0F, 0.0F));
        // Round 1 fix: a single flat plate read as "one board", not a propeller. Two thin crossed
        // blades (one long on X, one long on Z) approximate the reference logo's 4-blade propeller
        // while staying simple enough to hand-place UVs for - see make_drone_texture.py.
        rightArm.addOrReplaceChild(
                "right_rotor",
                CubeListBuilder.create()
                        .texOffs(0, 24).addBox(-3.0F, -0.5F, -1.0F, 6.0F, 1.0F, 2.0F, CubeDeformation.NONE)
                        .texOffs(0, 27).addBox(-1.0F, -0.5F, -3.0F, 2.0F, 1.0F, 6.0F, CubeDeformation.NONE),
                PartPose.offset(-4.0F, 0.0F, 0.0F));

        // Mirror of right_arm_light's joint - negative zRot so the raised angle mirrors left/right.
        PartDefinition leftArmLight = body.addOrReplaceChild(
                "left_arm_light",
                CubeListBuilder.create().texOffs(0, 57).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, CubeDeformation.NONE),
                PartPose.offsetAndRotation(3.5F, -5.5F, 0.0F, 0.0F, 0.0F, -0.45F));
        PartDefinition leftArm = leftArmLight.addOrReplaceChild(
                "left_arm",
                CubeListBuilder.create().texOffs(0, 20).addBox(0.0F, -0.5F, -0.5F, 4.0F, 1.0F, 1.0F, CubeDeformation.NONE),
                PartPose.offset(1.0F, 0.0F, 0.0F));
        leftArm.addOrReplaceChild(
                "left_rotor",
                CubeListBuilder.create()
                        .texOffs(0, 34).addBox(-3.0F, -0.5F, -1.0F, 6.0F, 1.0F, 2.0F, CubeDeformation.NONE)
                        .texOffs(0, 37).addBox(-1.0F, -0.5F, -3.0F, 2.0F, 1.0F, 6.0F, CubeDeformation.NONE),
                PartPose.offset(4.0F, 0.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(DroneEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);
        this.root.y += Mth.cos(ageInTicks * 0.1F) * 0.6F;
        this.rightRotor.yRot = ageInTicks * 3.5F;
        this.leftRotor.yRot = -ageInTicks * 3.5F;
    }
}
