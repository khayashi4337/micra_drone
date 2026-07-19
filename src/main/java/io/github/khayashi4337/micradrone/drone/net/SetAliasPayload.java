package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: set a human-readable alias for the controller at {@code pos} (coordinates alone are hard to tell apart). */
public record SetAliasPayload(BlockPos pos, String alias) implements CustomPacketPayload {
    public static final Type<SetAliasPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "set_alias"));
    public static final StreamCodec<ByteBuf, SetAliasPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetAliasPayload::pos,
            ByteBufCodecs.STRING_UTF8, SetAliasPayload::alias,
            SetAliasPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
