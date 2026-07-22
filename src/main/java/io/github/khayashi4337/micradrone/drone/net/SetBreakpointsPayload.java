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
 * breakpoint; applies immediately, mid-run included. See issue #6 (debugger).
 */
public record SetBreakpointsPayload(BlockPos pos, List<Integer> lines) implements CustomPacketPayload {
    public static final Type<SetBreakpointsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "set_breakpoints"));
    public static final StreamCodec<ByteBuf, SetBreakpointsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetBreakpointsPayload::pos,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.VAR_INT), SetBreakpointsPayload::lines,
            SetBreakpointsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
