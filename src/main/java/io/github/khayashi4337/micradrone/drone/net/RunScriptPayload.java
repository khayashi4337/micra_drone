package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: (re)load the script file for the controller at {@code pos} and start running it. */
public record RunScriptPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RunScriptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "run_script"));
    public static final StreamCodec<ByteBuf, RunScriptPayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, RunScriptPayload::pos, RunScriptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
