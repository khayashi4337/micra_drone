package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: spend the controller at {@code pos}'s points to buy {@code unlockId} from the shop (see UnlockShop.CATALOG). */
public record PurchaseUnlockPayload(BlockPos pos, String unlockId) implements CustomPacketPayload {
    public static final Type<PurchaseUnlockPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "purchase_unlock"));
    public static final StreamCodec<ByteBuf, PurchaseUnlockPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PurchaseUnlockPayload::pos,
            ByteBufCodecs.STRING_UTF8, PurchaseUnlockPayload::unlockId,
            PurchaseUnlockPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
