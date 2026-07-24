package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: inscribe the {@code SampleCatalog} entry at {@code sampleIndex} onto the blank script
 * scroll the sender holds (main or off hand), paid for at the enchanting table at
 * {@code tablePos}. Sent by {@code EnchantScrollScreen}; fully re-validated server-side by
 * {@code ScrollEnchanter} - see issue #8.
 */
public record EnchantScrollPayload(BlockPos tablePos, int sampleIndex, boolean mainHand) implements CustomPacketPayload {
    public static final Type<EnchantScrollPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "enchant_scroll"));
    public static final StreamCodec<ByteBuf, EnchantScrollPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, EnchantScrollPayload::tablePos,
            ByteBufCodecs.VAR_INT, EnchantScrollPayload::sampleIndex,
            ByteBufCodecs.BOOL, EnchantScrollPayload::mainHand,
            EnchantScrollPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
