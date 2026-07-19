package io.github.khayashi4337.micradrone.drone.net;

import java.util.HashSet;
import java.util.Set;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the controller at {@code pos}'s currently unlocked crop set, for DroneScreen's Shop tab. Kept
 * separate from {@link DroneLogPayload} because that record is already at StreamCodec.composite's
 * 6-field limit.
 */
public record ShopStatePayload(BlockPos pos, Set<String> unlockedCrops) implements CustomPacketPayload {
    public static final Type<ShopStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "shop_state"));
    public static final StreamCodec<ByteBuf, ShopStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ShopStatePayload::pos,
            ByteBufCodecs.collection(HashSet::new, ByteBufCodecs.STRING_UTF8), ShopStatePayload::unlockedCrops,
            ShopStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
