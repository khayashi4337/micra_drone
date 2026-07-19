package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: stop whatever script is running on the controller at {@code pos}. */
public record StopScriptPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<StopScriptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "stop_script"));
    public static final StreamCodec<ByteBuf, StopScriptPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, StopScriptPayload::pos, StopScriptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
