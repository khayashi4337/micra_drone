package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: joins the pages of the {@code ScriptScrollItem} the sender is holding in their main hand
 * into one script, saves it as the controller at {@code pos}'s {@code scroll.mdrone} script, and
 * runs it. Sent by {@code DroneScreen}'s "Run Scroll" button - see GitHub issue #1.
 */
public record RunScrollPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RunScrollPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "run_scroll"));
    public static final StreamCodec<ByteBuf, RunScrollPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, RunScrollPayload::pos, RunScrollPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
