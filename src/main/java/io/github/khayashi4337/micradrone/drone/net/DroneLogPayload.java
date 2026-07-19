package io.github.khayashi4337.micradrone.drone.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the controller at {@code pos}'s full current state snapshot (log buffer, points-per-crop,
 * available scripts, the currently selected one, and its alias), replacing whatever the client had
 * shown.
 */
public record DroneLogPayload(
        BlockPos pos,
        List<String> lines,
        Map<String, Long> pointsByCrop,
        List<String> availableScripts,
        String selectedScript,
        String alias) implements CustomPacketPayload {
    public static final Type<DroneLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "drone_log"));
    public static final StreamCodec<ByteBuf, DroneLogPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DroneLogPayload::pos,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), DroneLogPayload::lines,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_LONG), DroneLogPayload::pointsByCrop,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), DroneLogPayload::availableScripts,
            ByteBufCodecs.STRING_UTF8, DroneLogPayload::selectedScript,
            ByteBufCodecs.STRING_UTF8, DroneLogPayload::alias,
            DroneLogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
