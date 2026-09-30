package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: cancel the job {@code jobId} (M2). Owner-or-operator is judged by
 * {@code JobService.cancel} itself (D-12); the payload only carries the id.
 */
public record BuildCancelPayload(String jobId) implements CustomPacketPayload {
    /** Job ids match {@code ConstructionJob.ID_PATTERN}, which allows at most 64 chars. */
    private static final int MAX_JOB_ID_CHARS = 64;

    public static final Type<BuildCancelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_cancel"));
    public static final StreamCodec<ByteBuf, BuildCancelPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_JOB_ID_CHARS), BuildCancelPayload::jobId,
            BuildCancelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
