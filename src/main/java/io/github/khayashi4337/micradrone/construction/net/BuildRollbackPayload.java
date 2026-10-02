package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: roll the claim {@code claimId} back to its pre-build state (M5) - the panel's
 * もとにもどす button's confirmed ask. Owner-or-operator is judged by {@code JobService.rollback}
 * itself (D-12); the payload only carries the id.
 */
public record BuildRollbackPayload(String claimId) implements CustomPacketPayload {
    /**
     * Claim ids are {@code claim-} plus a job id ({@code ConstructionJob.ID_PATTERN}, at most 64
     * chars); this bound also leaves room for a hand-written claim id on a command line.
     */
    private static final int MAX_CLAIM_ID_CHARS = 64;

    public static final Type<BuildRollbackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_rollback"));
    public static final StreamCodec<ByteBuf, BuildRollbackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_CLAIM_ID_CHARS), BuildRollbackPayload::claimId,
            BuildRollbackPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
