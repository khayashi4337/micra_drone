package io.github.khayashi4337.micradrone.drone.net;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: sent to whichever player opened the Shop screen (via right-clicking a paired corner marker,
 * which resolves to the controller at {@code pos}). Self-sufficient for that screen: unlocked crops,
 * points-per-crop, and the plot's Corner Marker id are all included, since ShopScreen doesn't also
 * receive DroneLogPayload. {@code plotId} is what {@code get_plot_id()} would return - shown here so
 * a player can actually read it off the screen before writing it into a script (林さんの実機
 * フィードバック: マーカーをクリックしてもIDが見えないと、そもそもスクリプトで使えない).
 */
public record ShopStatePayload(BlockPos pos, Set<String> unlockedCrops, Map<String, Long> pointsByCrop, String plotId)
        implements CustomPacketPayload {
    public static final Type<ShopStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "shop_state"));
    public static final StreamCodec<ByteBuf, ShopStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ShopStatePayload::pos,
            ByteBufCodecs.collection(HashSet::new, ByteBufCodecs.STRING_UTF8), ShopStatePayload::unlockedCrops,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_LONG), ShopStatePayload::pointsByCrop,
            ByteBufCodecs.STRING_UTF8, ShopStatePayload::plotId,
            ShopStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
