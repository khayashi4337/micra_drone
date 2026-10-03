package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.build.ai.MaterialsDirective;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the claim {@code claimId}'s material rules (Task 27b) - the panel's "もちものからも
 * つかう?" button and the chat-voiced "ダイヤは つかわないで" share this one packet.
 * {@code inventory} is -1 for "leave the switch as it is", 0 for "do not use my things" and 1 for
 * "may use them"; {@code exclude}/{@code include} list the item ids to add to / remove from the
 * claim's ruled-out set. Owner-or-operator is judged by the runtime (same door as the rollback's);
 * the payload only carries the ask, bounded at the same caps the directive parser enforces.
 */
public record BuildMaterialsPayload(String claimId, int inventory, List<String> exclude,
                                    List<String> include) implements CustomPacketPayload {
    /** -1 leaves the inventory switch alone; 0 forbids the owner's things; 1 permits them. */
    public static final int INVENTORY_UNCHANGED = -1;
    /**
     * Claim ids are {@code claim-} plus a job id ({@code ConstructionJob.ID_PATTERN}, at most 64
     * chars) - the same bound {@link BuildRollbackPayload} already uses.
     */
    private static final int MAX_CLAIM_ID_CHARS = 64;

    public static final Type<BuildMaterialsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_materials"));
    public static final StreamCodec<ByteBuf, BuildMaterialsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_CLAIM_ID_CHARS), BuildMaterialsPayload::claimId,
            ByteBufCodecs.VAR_INT, BuildMaterialsPayload::inventory,
            ByteBufCodecs.stringUtf8(MaterialsDirective.MAX_ITEM_ID_CHARS)
                    .apply(ByteBufCodecs.list(MaterialsDirective.MAX_ITEMS)),
            BuildMaterialsPayload::exclude,
            ByteBufCodecs.stringUtf8(MaterialsDirective.MAX_ITEM_ID_CHARS)
                    .apply(ByteBufCodecs.list(MaterialsDirective.MAX_ITEMS)),
            BuildMaterialsPayload::include,
            BuildMaterialsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
