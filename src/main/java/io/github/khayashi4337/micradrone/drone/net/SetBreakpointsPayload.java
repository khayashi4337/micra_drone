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
 * C2S: replace the controller at {@code pos}'s debugger breakpoint set with {@code lines}
 * (1-based script line numbers). Sent by {@code IdeScreen} whenever a gutter click toggles a
 * breakpoint, or its own edit-time line tracking retargets one; applies immediately, mid-run
 * included. See issue #6 (debugger).
 *
 * <p>{@code revision} is a per-client, strictly-increasing counter {@code IdeScreen} bumps on every
 * send (see its {@code sendBreakpoints}) - echoed back verbatim in {@code DebugStatePayload}, so the
 * sender can tell a stale echo of an earlier send (server hasn't caught up to the client's latest
 * edit yet) apart from a fresh one, and ignore the former. Fast local edits (holding Enter above a
 * breakpoint) send several of these before the first echo returns; without the revision, that first
 * echo would overwrite the client's already-further-along local state with older data mid-edit -
 * the exact bug this field exists to prevent (real-machine report: a breakpoint drifted off the
 * line it was set on during rapid editing). Meaningless to any client other than the one that sent
 * it - each client numbers its own sends independently, so another viewer's {@code DebugStatePayload}
 * is compared against ITS OWN last-sent revision, not this one.
 */
public record SetBreakpointsPayload(BlockPos pos, List<Integer> lines, int revision) implements CustomPacketPayload {
    public static final Type<SetBreakpointsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "set_breakpoints"));
    public static final StreamCodec<ByteBuf, SetBreakpointsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetBreakpointsPayload::pos,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), SetBreakpointsPayload::lines,
            ByteBufCodecs.VAR_INT, SetBreakpointsPayload::revision,
            SetBreakpointsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
