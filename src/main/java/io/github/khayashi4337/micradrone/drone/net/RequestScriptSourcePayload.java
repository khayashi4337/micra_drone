package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: fetch {@code scriptName}'s source from the controller at {@code pos}'s script folder, to be
 * answered with a {@link ScriptSourcePayload}. Sent when {@code IdeScreen} opens - see issue #6.
 */
public record RequestScriptSourcePayload(BlockPos pos, String scriptName) implements CustomPacketPayload {
    public static final Type<RequestScriptSourcePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "request_script_source"));
    public static final StreamCodec<ByteBuf, RequestScriptSourcePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RequestScriptSourcePayload::pos,
            ByteBufCodecs.STRING_UTF8, RequestScriptSourcePayload::scriptName,
            RequestScriptSourcePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
