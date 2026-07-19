package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: sent when a {@code DroneScreen} opens, to fetch the controller's current log snapshot. */
public record RequestLogPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "request_log"));
    public static final StreamCodec<ByteBuf, RequestLogPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, RequestLogPayload::pos, RequestLogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
