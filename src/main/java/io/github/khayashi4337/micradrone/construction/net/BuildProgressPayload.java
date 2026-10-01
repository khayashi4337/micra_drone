package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: one job's progress (M2) - the {@code ProgressView} table as one JSON document. The document
 * is a fixed-shape single-job table, small enough for the default string bound.
 */
public record BuildProgressPayload(String progressJson) implements CustomPacketPayload {
    public static final Type<BuildProgressPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_progress"));
    public static final StreamCodec<ByteBuf, BuildProgressPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BuildProgressPayload::progressJson,
            BuildProgressPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
