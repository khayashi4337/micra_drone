package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: sent when a ShopScreen opens, {@code pos} is the corner marker that was right-clicked (not a
 * controller). The server reverse-scans from it to find the paired controller and replies with
 * {@link ShopStatePayload} keyed by the controller's actual position.
 */
public record RequestShopStatePayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestShopStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "request_shop_state"));
    public static final StreamCodec<ByteBuf, RequestShopStatePayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, RequestShopStatePayload::pos, RequestShopStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
