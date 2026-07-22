package io.github.khayashi4337.micradrone.drone.net;

import java.util.ArrayList;
import java.util.List;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the controller at {@code pos}'s debugger snapshot - run state, the line about to execute
 * ({@code currentLine}, 1-based; 0 while idle), and the server-held breakpoint set (so a reopened
 * {@code IdeScreen} gets its breakpoints back). Pushed to the viewing player when something
 * changed (at most once per tick - see DroneControllerBlockEntity#maybePushDebugState) and
 * immediately when the IDE opens. See issue #6 (debugger).
 */
public record DebugStatePayload(BlockPos pos, int state, int currentLine, List<Integer> breakpoints) implements CustomPacketPayload {
    public static final int STATE_IDLE = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_PAUSED = 2;

    public static final Type<DebugStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "debug_state"));
    public static final StreamCodec<ByteBuf, DebugStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DebugStatePayload::pos,
            ByteBufCodecs.VAR_INT, DebugStatePayload::state,
            ByteBufCodecs.VAR_INT, DebugStatePayload::currentLine,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), DebugStatePayload::breakpoints,
            DebugStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
