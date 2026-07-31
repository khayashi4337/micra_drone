package io.github.khayashi4337.micradrone.drone;

import com.mojang.serialization.Dynamic;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Visible drone: a plain {@link Allay} subclass, reused purely for its physics/hitbox/save-format
 * plumbing - not for its looks. Its look is fully custom (see the client-only {@code DroneModel}/
 * {@code DroneRenderer}). All of Allay's own behavior is suppressed - {@link #makeBrain} skips
 * AllayAi's activity wiring entirely (no wandering, no note-block duplication, no item pickup), and
 * {@link #mobInteract} disables the vanilla "give it an item" interaction. Its position is driven
 * entirely by {@link DroneControllerBlockEntity}, in lockstep with the drone's grid position.
 */
public class DroneEntity extends Allay {
    /** do_a_flip() duration - see {@link #startFlip}, {@code DroneModel#setupAnim}. */
    public static final int FLIP_TICKS = 8;

    /**
     * {@code tickCount} at the start of the current/most recent somersault, or {@code Integer.MIN_VALUE}
     * before the first one - synced (issue: {@code startFlip} only ever runs server-side, from
     * {@code DroneControllerBlockEntity#triggerDroneFlip}, but the animation itself is purely a
     * client-side render concern in {@code DroneModel#setupAnim}, which needs this value on ITS side
     * too). {@link SynchedEntityData} is the standard vanilla mechanism for exactly this - a plain
     * server-only field would never reach the client's copy of this entity.
     */
    private static final EntityDataAccessor<Integer> DATA_FLIP_START_TICK =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.INT);

    public DroneEntity(EntityType<? extends Allay> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FLIP_START_TICK, Integer.MIN_VALUE);
    }

    /**
     * Starts (or restarts, if already mid-somersault) a one-shot 360-degree tumble - a real
     * somersault (rotating forward/back around the horizontal axis), not a turn-in-place spin;
     * 林さんのフィードバック (最初の実装はyawの回転で、宙返りの仕様を誤解していた) . See
     * {@code DroneApi#doAFlip}.
     */
    public void startFlip() {
        this.entityData.set(DATA_FLIP_START_TICK, this.tickCount);
    }

    /** The {@code tickCount} the current/most recent flip started at - see {@code DroneModel#setupAnim}. */
    public int flipStartTick() {
        return this.entityData.get(DATA_FLIP_START_TICK);
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        // Deliberately skip AllayAi.makeBrain(...): keeps the same memory/sensor slots (via the
        // inherited brainProvider()) so nothing NPEs, but registers zero activities/behaviors, so
        // the brain never does anything on its own.
        return this.brainProvider().makeBrain(dynamic);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    @Override
    public void tick() {
        super.tick();
        // The reference artwork shows a teal jet streaming down from the thruster nozzle. Soul fire
        // flame is vanilla's teal flame and (via RisingParticle, verified in decompiled sources) it
        // honors the velocity passed here, so a small downward speed makes it stream down instead of
        // rising like a normal flame. Client side only - purely cosmetic.
        if (this.level().isClientSide && this.tickCount % 3 == 0) {
            this.level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, this.getX(), this.getY() + 0.1, this.getZ(), 0.0, -0.06, 0.0);
        }
    }
}
