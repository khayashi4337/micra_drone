package io.github.khayashi4337.micradrone.drone.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * S2C: the controller at {@code pos}'s full current state snapshot (log buffer, points-per-crop,
 * unlocked crops/features, available scripts - on-disk files and chest scrolls alike, see
 * {@link ScriptEntry} - the currently selected one's id, and the alias), replacing whatever the
 * client had shown. {@code unlockedCrops} rides along so the AI chat can tell the player what is
 * and isn't unlocked yet (real-machine finding: without it the AI planned carrot scripts on a plot
 * that hadn't bought carrots); the Shop screen keeps its own ShopStatePayload.
 */
public record DroneLogPayload(
        BlockPos pos,
        List<String> lines,
        Map<String, Long> pointsByCrop,
        Set<String> unlockedCrops,
        List<ScriptEntry> scripts,
        String selectedScript,
        String alias) implements CustomPacketPayload {
    public static final Type<DroneLogPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "drone_log"));
    private static final StreamCodec<ByteBuf, List<String>> LINES_CODEC =
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8);
    private static final StreamCodec<ByteBuf, Map<String, Long>> POINTS_CODEC =
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_LONG);
    private static final StreamCodec<ByteBuf, Set<String>> UNLOCKS_CODEC =
            ByteBufCodecs.collection(HashSet::new, ByteBufCodecs.STRING_UTF8);
    private static final StreamCodec<ByteBuf, List<ScriptEntry>> SCRIPTS_CODEC =
            ByteBufCodecs.collection(ArrayList::new, ScriptEntry.STREAM_CODEC);
    // Seven fields: one past what StreamCodec.composite offers, so encode/decode are spelled out.
    public static final StreamCodec<ByteBuf, DroneLogPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                LINES_CODEC.encode(buf, payload.lines());
                POINTS_CODEC.encode(buf, payload.pointsByCrop());
                UNLOCKS_CODEC.encode(buf, payload.unlockedCrops());
                SCRIPTS_CODEC.encode(buf, payload.scripts());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.selectedScript());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.alias());
            },
            buf -> new DroneLogPayload(
                    BlockPos.STREAM_CODEC.decode(buf),
                    LINES_CODEC.decode(buf),
                    POINTS_CODEC.decode(buf),
                    UNLOCKS_CODEC.decode(buf),
                    SCRIPTS_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
