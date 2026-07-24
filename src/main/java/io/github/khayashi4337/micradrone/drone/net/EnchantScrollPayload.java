package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: inscribe onto the blank script scroll sitting in the sender's currently-open enchanting
 * table menu (item slot), paid for at the table at {@code tablePos}. {@code sampleIndex >= 0}
 * picks a {@code SampleCatalog} entry (fixed indexes {@code bookshelfOffsetIndex}/{@code copySlot}
 * at -1); {@code sampleIndex < 0} instead copies the written scroll sitting in a bookshelf around
 * the table, identified by {@code bookshelfOffsetIndex} (into
 * {@code EnchantingTableBlock.BOOKSHELF_OFFSETS}) and {@code copySlot} (the bookshelf's own slot).
 * Sent by {@code EnchantScrollScreen}; fully re-validated server-side by {@code ScrollEnchanter} -
 * see issue #8 (samples) and the GUI-reduction follow-up (bookshelf copies).
 */
public record EnchantScrollPayload(BlockPos tablePos, int sampleIndex, int bookshelfOffsetIndex, int copySlot)
        implements CustomPacketPayload {
    public static final Type<EnchantScrollPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "enchant_scroll"));
    public static final StreamCodec<ByteBuf, EnchantScrollPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, EnchantScrollPayload::tablePos,
            ByteBufCodecs.VAR_INT, EnchantScrollPayload::sampleIndex,
            ByteBufCodecs.VAR_INT, EnchantScrollPayload::bookshelfOffsetIndex,
            ByteBufCodecs.VAR_INT, EnchantScrollPayload::copySlot,
            EnchantScrollPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
