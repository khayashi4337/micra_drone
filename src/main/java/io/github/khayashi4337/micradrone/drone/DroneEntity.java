package io.github.khayashi4337.micradrone.drone;

import com.mojang.serialization.Dynamic;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Visible drone: a plain {@link Allay} subclass so it can reuse the vanilla Allay model/renderer/
 * texture/animations as-is (AllayRenderer/AllayModel are generically bound to Allay, so this is the
 * only way to reuse them without hand-copying the model). All of Allay's own behavior is suppressed -
 * {@link #makeBrain} skips AllayAi's activity wiring entirely (no wandering, no note-block duplication,
 * no item pickup), and {@link #mobInteract} disables the vanilla "give it an item" interaction. Its
 * position is driven entirely by {@link DroneControllerBlockEntity}, in lockstep with the drone's grid
 * position.
 */
public class DroneEntity extends Allay {
    public DroneEntity(EntityType<? extends Allay> entityType, Level level) {
        super(entityType, level);
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
}
