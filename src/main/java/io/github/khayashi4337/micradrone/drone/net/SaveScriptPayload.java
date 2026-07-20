package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: write {@code source} as {@code scriptName} in the controller at {@code pos}'s script
 * folder. Sent by {@code IdeScreen}'s Save button - see issue #6. The server re-validates the
 * name ({@code ScriptFileStore#isValidScriptName}) and length; failures are reported to the
 * player's chat.
 */
public record SaveScriptPayload(BlockPos pos, String scriptName, String source) implements CustomPacketPayload {
    public static final Type<SaveScriptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "save_script"));
    public static final StreamCodec<ByteBuf, SaveScriptPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveScriptPayload::pos,
            ByteBufCodecs.STRING_UTF8, SaveScriptPayload::scriptName,
            ByteBufCodecs.STRING_UTF8, SaveScriptPayload::source,
            SaveScriptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
