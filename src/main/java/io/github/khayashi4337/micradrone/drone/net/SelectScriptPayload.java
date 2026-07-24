package io.github.khayashi4337.micradrone.drone.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: mark {@code scriptId} as the controller at {@code pos}'s selected script, without running
 * or saving anything - sent when the player clicks an entry in the IDE's script list (GUI
 * reduction follow-up: list selection is now the sole way to pick which script a redstone signal
 * runs, replacing the retired jukebox-style item slot).
 */
public record SelectScriptPayload(BlockPos pos, String scriptId) implements CustomPacketPayload {
    public static final Type<SelectScriptPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "select_script"));
    public static final StreamCodec<ByteBuf, SelectScriptPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SelectScriptPayload::pos,
            ByteBufCodecs.STRING_UTF8, SelectScriptPayload::scriptId,
            SelectScriptPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
