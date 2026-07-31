package io.github.khayashi4337.micradrone.drone;

import com.mojang.serialization.Dynamic;

import net.minecraft.core.particles.ParticleTypes;
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
    /** 45 deg/tick * 8 ticks = one full turn in 0.4s - fast enough to read as a "flip", not a snap. */
    private static final int FLIP_TICKS = 8;
    private static final float FLIP_DEGREES_PER_TICK = 360F / FLIP_TICKS;

    /** do_a_flip(): counts down while the entity spins itself once around the vertical axis. */
    private int flipTicksRemaining;

    public DroneEntity(EntityType<? extends Allay> entityType, Level level) {
        super(entityType, level);
    }

    /** Starts (or restarts, if already mid-flip) a one-shot 360-degree spin - see {@code DroneApi#doAFlip}. */
    public void startFlip() {
        flipTicksRemaining = FLIP_TICKS;
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
        // yBodyRot (not yRot) is what LivingEntityRenderer#setupRotations actually turns the model
        // by (verified in decompiled sources) - AllayAi's look control is disabled along with the
        // rest of its brain, so nothing else would ever move it. super.tick() (LivingEntity#tick)
        // already copied yBodyRotO = yBodyRot for this tick before this runs, so mutating yBodyRot
        // here is exactly what vanilla's own render-time interpolation expects for smooth motion.
        if (flipTicksRemaining > 0) {
            flipTicksRemaining--;
            this.yBodyRot += FLIP_DEGREES_PER_TICK;
        }
        // The reference artwork shows a teal jet streaming down from the thruster nozzle. Soul fire
        // flame is vanilla's teal flame and (via RisingParticle, verified in decompiled sources) it
        // honors the velocity passed here, so a small downward speed makes it stream down instead of
        // rising like a normal flame. Client side only - purely cosmetic.
        if (this.level().isClientSide && this.tickCount % 3 == 0) {
            this.level().addParticle(ParticleTypes.SOUL_FIRE_FLAME, this.getX(), this.getY() + 0.1, this.getZ(), 0.0, -0.06, 0.0);
        }
    }
}
