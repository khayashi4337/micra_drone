package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: one debugger action for the script running on the controller at {@code pos} - see the
 * COMMAND_* constants. Ignored server-side when no script is running. Sent by {@code IdeScreen}'s
 * debug buttons; see issue #6 (debugger).
 */
public record DebugCommandPayload(BlockPos pos, int command) implements CustomPacketPayload {
    public static final int COMMAND_PAUSE = 0;
    public static final int COMMAND_RESUME = 1;
    public static final int COMMAND_STEP = 2;
    public static final int COMMAND_STEP_OUT = 3;

    public static final Type<DebugCommandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "debug_command"));
    public static final StreamCodec<ByteBuf, DebugCommandPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DebugCommandPayload::pos,
            ByteBufCodecs.VAR_INT, DebugCommandPayload::command,
            DebugCommandPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
