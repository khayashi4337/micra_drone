package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: write {@code scriptName}'s source (loaded from the controller at {@code pos}'s script
 * folder) into the scroll the sender is holding in their main hand ({@code mainHand=true}) or off
 * hand. Sent by {@code ScrollPickScreen} when a script is picked to fill a blank scroll - see
 * GitHub issue #1.
 */
public record FillScrollPayload(BlockPos pos, boolean mainHand, String scriptName) implements CustomPacketPayload {
    public static final Type<FillScrollPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "fill_scroll"));
    public static final StreamCodec<ByteBuf, FillScrollPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FillScrollPayload::pos,
            ByteBufCodecs.BOOL, FillScrollPayload::mainHand,
            ByteBufCodecs.STRING_UTF8, FillScrollPayload::scriptName,
            FillScrollPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
