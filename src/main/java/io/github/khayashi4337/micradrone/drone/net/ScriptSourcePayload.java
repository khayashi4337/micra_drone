package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: {@code scriptName}'s full source text from the controller at {@code pos}'s script folder -
 * the answer to a {@link RequestScriptSourcePayload}, loaded into {@code IdeScreen}'s editor.
 * See issue #6.
 */
public record ScriptSourcePayload(BlockPos pos, String scriptName, String source) implements CustomPacketPayload {
    public static final Type<ScriptSourcePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "script_source"));
    public static final StreamCodec<ByteBuf, ScriptSourcePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScriptSourcePayload::pos,
            ByteBufCodecs.STRING_UTF8, ScriptSourcePayload::scriptName,
            ByteBufCodecs.STRING_UTF8, ScriptSourcePayload::source,
            ScriptSourcePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
