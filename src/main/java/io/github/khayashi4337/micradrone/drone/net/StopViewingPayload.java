package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: sent when a controller screen closes, so the server stops pushing that controller's updates
 * to this player. The counterpart to the request payloads each screen sends on opening - without it
 * a player who once opened a screen would keep receiving its packets for the rest of the session.
 * {@code pos} is whatever block the screen was opened on: a controller for Scripts/IDE, a corner
 * marker for the Shop (resolved back to its controller server-side, the same way opening it was).
 */
public record StopViewingPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<StopViewingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "stop_viewing"));
    public static final StreamCodec<ByteBuf, StopViewingPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, StopViewingPayload::pos, StopViewingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
