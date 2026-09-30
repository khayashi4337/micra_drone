package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.construction.core.ChildMessages;
import io.github.khayashi4337.micradrone.construction.core.ConstructionBudget;
import io.github.khayashi4337.micradrone.construction.core.ConstructionJob;
import io.github.khayashi4337.micradrone.construction.core.DroneChoreographer;
import io.github.khayashi4337.micradrone.construction.core.DroneMove;
import io.github.khayashi4337.micradrone.construction.core.JobService;
import io.github.khayashi4337.micradrone.construction.core.JobState;
import io.github.khayashi4337.micradrone.construction.core.JobUpdate;
import io.github.khayashi4337.micradrone.construction.core.MessageKey;
import io.github.khayashi4337.micradrone.drone.DroneEntity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The drone show of a build (02 N-27): while a job places blocks, a small fleet of tagged drones hovers
 * over the freshly placed positions, each position sparkles and plays its block's place sound. The show
 * is decoration only - the drones are never written to the world (a tagged one loaded from disk is refused,
 * all are discarded at server stop) and removing them by /kill leaves the job untouched; the next placing
 * tick simply brings the fleet back. The assignments come from the pure {@link DroneChoreographer}; this
 * class only maps them onto Minecraft entities.
 */
public final class DroneShow {
    /** Tag on every show drone, so leftovers of a previous run and /kill selectors can find them. */
    public static final String SHOW_TAG = "micradrone_build_show";
    /** Sparkles over each freshly placed position. */
    static final int SHOW_PARTICLES = 4;
    /** A tick plays at most this many place sounds so overlapping placements do not stack into noise. */
    static final int MAX_SOUNDS_PER_TICK = 4;
    /** The sparkle scatter around a placed block's centre, matching PumpkinEffects' sparkles. */
    private static final double SPARKLE_SPREAD = 0.45;
    private static final double SPARKLE_SPEED = 0.0;
    /** Vanilla BlockItem plays a place sound at (volume + 1) / 2 and pitch * 0.8 of the sound type. */
    private static final float SOUND_VOLUME_PADDING = 1.0f;
    private static final float SOUND_VOLUME_DIVISOR = 2.0f;
    private static final float SOUND_PITCH_FACTOR = 0.8f;
    /** A drone hovers over a block's centre, not its corner. */
    private static final double CENTRE = 0.5;
    /** Only a job that is placing or repairing gets the show (N-27). */
    private static final Set<JobState> SHOWING = EnumSet.of(JobState.RUNNING, JobState.REPAIRING);

    /** Job id -> the UUIDs of its show drones, in fleet order. */
    private final Map<String, List<UUID>> dronesByJob = new HashMap<>();
    /** Jobs whose owner already heard DRONE_ARRIVED once (a pause/resume does not repeat it). */
    private final Set<String> announced = new HashSet<>();

    /**
     * Tick step (5): the positions placed this tick get drones over them, sparkles, and the placed block's
     * sound. A job leaving the showing states loses its drones; a cancel arrives without an update, so the
     * sweep afterwards also asks the service directly.
     */
    void onUpdates(MinecraftServer server, List<JobUpdate> updates) {
        int sounds = 0;
        for (JobUpdate update : updates) {
            ConstructionJob job = update.job();
            if (!SHOWING.contains(job.state())) {
                discard(server, job.jobId());
                continue;
            }
            if (update.touched().isEmpty()) {
                continue;
            }
            ServerLevel level = levelOf(server, job.dimension());
            if (level != null) {
                sounds = show(server, level, job, update.touched(), sounds);
            }
        }
        sweep(server);
    }

    /** The fleet for one update: enough drones, each flown to its assigned position, then sights and sounds. */
    private int show(MinecraftServer server, ServerLevel level, ConstructionJob job, List<IntPos> touched,
                     int sounds) {
        int count = ConstructionBudget.droneCount(job.total(), ConstructionConfig.budget());
        List<Entity> live = ensureDrones(server, level, job, touched.get(0), count);
        List<DroneMove> moves = DroneChoreographer.assign(count, touched);
        for (int i = 0; i < moves.size() && i < live.size(); i++) {
            IntPos target = moves.get(i).target();
            live.get(i).moveTo(target.x() + CENTRE, target.y() + DroneChoreographer.HOVER_BLOCKS, target.z() + CENTRE);
        }
        for (IntPos pos : touched) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.x() + CENTRE, pos.y() + CENTRE, pos.z() + CENTRE,
                    SHOW_PARTICLES, SPARKLE_SPREAD, SPARKLE_SPREAD, SPARKLE_SPREAD, SPARKLE_SPEED);
            if (sounds < MAX_SOUNDS_PER_TICK) {
                BlockPos at = ServerWorldPort.toBlockPos(pos);
                SoundType sound = level.getBlockState(at).getSoundType(level, at, null);
                level.playSound(null, at, sound.getPlaceSound(), SoundSource.BLOCKS,
                        (sound.getVolume() + SOUND_VOLUME_PADDING) / SOUND_VOLUME_DIVISOR,
                        sound.getPitch() * SOUND_PITCH_FACTOR);
                sounds++;
            }
        }
        return sounds;
    }

    /**
     * The job's fleet, grown to {@code count} drones: dead entries are pruned, missing ones spawn above the
     * first fresh position. The owner hears DRONE_ARRIVED the first time a job's fleet takes off.
     */
    private List<Entity> ensureDrones(MinecraftServer server, ServerLevel level, ConstructionJob job, IntPos first,
                                      int count) {
        List<UUID> ids = dronesByJob.computeIfAbsent(job.jobId(), id -> new ArrayList<>());
        List<Entity> live = liveDrones(server, ids);
        while (live.size() < count) {
            DroneEntity drone = MicraDrone.DRONE_ENTITY.get().create(level);
            if (drone == null) {
                break;
            }
            drone.addTag(SHOW_TAG);
            drone.setNoGravity(true);
            drone.setInvulnerable(true);
            drone.moveTo(first.x() + CENTRE, first.y() + DroneChoreographer.HOVER_BLOCKS, first.z() + CENTRE);
            level.addFreshEntity(drone);
            ids.add(drone.getUUID());
            live.add(drone);
        }
        if (!live.isEmpty() && announced.add(job.jobId())) {
            ServerMessages.send(server, job.ownerUuid(), MessageKey.of(ChildMessages.DRONE_ARRIVED));
        }
        return live;
    }

    /** The live entities behind the tracked ids; ids whose entity is gone (killed, unloaded) are dropped. */
    private static List<Entity> liveDrones(MinecraftServer server, List<UUID> ids) {
        List<Entity> live = new ArrayList<>(ids.size());
        Iterator<UUID> it = ids.iterator();
        while (it.hasNext()) {
            Entity entity = entity(server, it.next());
            if (entity == null) {
                it.remove();
            } else {
                live.add(entity);
            }
        }
        return live;
    }

    /**
     * The service is the truth on whether a job still shows: a cancel or a control command produces no
     * update, so tracked jobs whose state left RUNNING/REPAIRING are discarded here once a tick.
     */
    private void sweep(MinecraftServer server) {
        if (dronesByJob.isEmpty()) {
            return;
        }
        Optional<JobService> service = ConstructionRuntime.of(server).map(ConstructionRuntime::jobs);
        for (String jobId : new ArrayList<>(dronesByJob.keySet())) {
            boolean showing = service.flatMap(s -> s.status(jobId))
                    .map(status -> SHOWING.contains(status.state()))
                    .orElse(false);
            if (!showing) {
                discard(server, jobId);
            }
        }
    }

    /** One job's drones, wherever they are loaded; the job keeps no fleet afterwards. */
    private void discard(MinecraftServer server, String jobId) {
        List<UUID> ids = dronesByJob.remove(jobId);
        if (ids != null) {
            discard(server, ids);
        }
    }

    /** Every show drone this runtime made (server stop: none of them may be written into the world). */
    void discardAll(MinecraftServer server) {
        for (String jobId : new ArrayList<>(dronesByJob.keySet())) {
            discard(server, jobId);
        }
        announced.clear();
    }

    private void discard(MinecraftServer server, List<UUID> ids) {
        for (UUID id : ids) {
            Entity entity = entity(server, id);
            if (entity != null) {
                entity.discard();
            }
        }
    }

    private static Entity entity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
    }

    /** A show drone arriving from disk is a leftover of a previous run: it was never meant to be saved. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity().getTags().contains(SHOW_TAG)) {
            event.setCanceled(true);
        }
    }
}
