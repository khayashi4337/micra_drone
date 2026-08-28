package io.github.khayashi4337.micradrone.client;

import java.util.Optional;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import io.github.khayashi4337.micradrone.chat.RegionSelectionState;
import io.github.khayashi4337.micradrone.drone.RegionPointerItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws RegionPointerItem's selection in the world, the way Create's super-glue selection does
 * (SuperGlueSelectionHandler + its AABBOutline, studied for this): once the start corner is
 * clicked, a box stretches live from that corner to whatever block the crosshair is on, so the
 * player sees the region grow before committing it; after the end corner is clicked the box
 * stays put until the AI chat consumes it. Rendering itself is plain vanilla - translucent faces
 * via the same {@code RenderType.debugFilledBox()} DebugRenderer uses and edges via
 * {@code RenderType.lines()}/LevelRenderer#renderLineBox (the block-outline render type) - rather
 * than Create's custom thick-line render types, which need shaders this mod doesn't ship.
 *
 * <p>Instance-level {@code @SubscribeEvent} registered from MicraDroneClient, like
 * RegionPointerListener - see EnchantTableWatcher for why not a static subscriber.
 */
public final class RegionSelectionRenderer {
    // The mod's cyan accent (the controller's screen glow), so the box reads as "ours".
    private static final float RED = 110 / 255f;
    private static final float GREEN = 240 / 255f;
    private static final float BLUE = 255 / 255f;
    /** The live corner-to-crosshair preview: faint, so it never hides what you're aiming at. */
    private static final float PREVIEW_FACE_ALPHA = 0.12f;
    private static final float PREVIEW_EDGE_ALPHA = 0.7f;
    /** The committed selection: clearly stronger than the preview, still see-through. */
    private static final float SELECTED_FACE_ALPHA = 0.28f;
    private static final float SELECTED_EDGE_ALPHA = 1.0f;
    /** The first clicked block stays marked so the player sees where the box is anchored. */
    private static final float ANCHOR_EDGE_ALPHA = 1.0f;
    /** Nudged out so the faces don't z-fight with the block faces they sit on (Create: 1/128). */
    private static final double SURFACE_INFLATE = 1 / 128.0;

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        // Translucent geometry: the particles stage is what NeoForge documents for that.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        RegionSelectionState selection = RegionSelectionHolder.PENDING;
        Optional<RegionSelectionState.Corner> start = selection.corner1();
        if (start.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        BlockPos startPos = new BlockPos(start.get().x(), start.get().y(), start.get().z());
        Optional<RegionSelectionState.Corner> end = selection.corner2();
        boolean holdingPointer = minecraft.player.getMainHandItem().getItem() instanceof RegionPointerItem;

        AABB box;
        float faceAlpha;
        float edgeAlpha;
        if (end.isPresent()) {
            // Committed: shown whatever is in hand, so it survives swapping to open the IDE.
            BlockPos endPos = new BlockPos(end.get().x(), end.get().y(), end.get().z());
            box = AABB.encapsulatingFullBlocks(startPos, endPos);
            faceAlpha = SELECTED_FACE_ALPHA;
            edgeAlpha = SELECTED_EDGE_ALPHA;
        } else if (holdingPointer && minecraft.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            // Live preview, only while the pointer is out and aimed at a block.
            box = AABB.encapsulatingFullBlocks(startPos, hit.getBlockPos());
            faceAlpha = PREVIEW_FACE_ALPHA;
            edgeAlpha = PREVIEW_EDGE_ALPHA;
        } else {
            box = null;
            faceAlpha = 0;
            edgeAlpha = 0;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        if (box != null) {
            AABB inflated = box.inflate(SURFACE_INFLATE);
            DebugRenderer.renderFilledBox(poseStack, buffers, inflated, RED, GREEN, BLUE, faceAlpha);
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            LevelRenderer.renderLineBox(poseStack, lines, inflated, RED, GREEN, BLUE, edgeAlpha);
        }
        // Anchor outline on the first corner, drawn last so it stays crisp on top of the faces.
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, lines, new AABB(startPos).inflate(SURFACE_INFLATE * 2),
                RED, GREEN, BLUE, ANCHOR_EDGE_ALPHA);
        poseStack.popPose();

        buffers.endBatch(RenderType.debugFilledBox());
        buffers.endBatch(RenderType.lines());
    }
}
