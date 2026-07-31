package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: rename the scroll {@code scriptId} points at to {@code newName} - same effect as a vanilla
 * anvil rename, just triggered by double-clicking the title in {@code IdeScreen} (林さんの要望).
 * The server re-resolves {@code scriptId} and re-validates the name; failures are reported to the
 * player's chat.
 */
public record RenameScriptPayload(BlockPos pos, String scriptId, String newName) implements CustomPacketPayload {
    public static final Type<RenameScriptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "rename_script"));
    public static final StreamCodec<ByteBuf, RenameScriptPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RenameScriptPayload::pos,
            ByteBufCodecs.STRING_UTF8, RenameScriptPayload::scriptId,
            ByteBufCodecs.STRING_UTF8, RenameScriptPayload::newName,
            RenameScriptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
