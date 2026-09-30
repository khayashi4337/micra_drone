package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.construction.core.PlanChunkCheck;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: one chunk of a plan upload (M2). The shape already carries {@code transferId}/{@code index}/
 * {@code count} so chunked uploads (a later task) do not force a new payload type; the MVP's server
 * accepts only {@code index == 0 && count == 1} (see {@link PlanChunkCheck}). {@code here} asks for
 * the plan's site to be relocated under the sender's feet, like {@code submit-here}.
 */
public record BuildPlanPayload(String transferId, int index, int count, String chunk, boolean here)
        implements CustomPacketPayload {
    public static final Type<BuildPlanPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_plan"));
    public static final StreamCodec<ByteBuf, BuildPlanPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(PlanChunkCheck.MAX_TRANSFER_ID_CHARS), BuildPlanPayload::transferId,
            ByteBufCodecs.VAR_INT, BuildPlanPayload::index,
            ByteBufCodecs.VAR_INT, BuildPlanPayload::count,
            ByteBufCodecs.stringUtf8(PlanChunkCheck.MAX_CHUNK_CHARS), BuildPlanPayload::chunk,
            ByteBufCodecs.BOOL, BuildPlanPayload::here,
            BuildPlanPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
