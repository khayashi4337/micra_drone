package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S: the owner's answer to an offer (M2) - approve the manifest of {@code hash}, with the
 * terraforming and destructive-replacement confirmations ticked or not. The server's approval desk
 * re-checks the hash, the owner and the dimension; this payload only carries the answer.
 */
public record BuildApprovePayload(String hash, boolean confirmTerraform, boolean confirmDestructive)
        implements CustomPacketPayload {
    /** Manifest hashes are sha-256 hex (64 chars); the bound leaves headroom for a prefix. */
    private static final int MAX_HASH_CHARS = 128;

    public static final Type<BuildApprovePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_approve"));
    public static final StreamCodec<ByteBuf, BuildApprovePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_HASH_CHARS), BuildApprovePayload::hash,
            ByteBufCodecs.BOOL, BuildApprovePayload::confirmTerraform,
            ByteBufCodecs.BOOL, BuildApprovePayload::confirmDestructive,
            BuildApprovePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
