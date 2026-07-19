package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: (re)load {@code scriptName} from the controller at {@code pos}'s script folder and run it. */
public record RunScriptPayload(BlockPos pos, String scriptName) implements CustomPacketPayload {
    public static final Type<RunScriptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "run_script"));
    public static final StreamCodec<ByteBuf, RunScriptPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunScriptPayload::pos,
            ByteBufCodecs.STRING_UTF8, RunScriptPayload::scriptName,
            RunScriptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
