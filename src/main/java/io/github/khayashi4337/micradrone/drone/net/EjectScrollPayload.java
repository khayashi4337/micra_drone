package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: pop the scroll slotted in the controller at {@code pos} back out into the world (issue #7,
 * the jukebox-style controller slot). Sent by {@code DroneScreen}'s Eject button; the server
 * answers with a chat reason when nothing is slotted.
 */
public record EjectScrollPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<EjectScrollPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "eject_scroll"));
    public static final StreamCodec<ByteBuf, EjectScrollPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, EjectScrollPayload::pos, EjectScrollPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
