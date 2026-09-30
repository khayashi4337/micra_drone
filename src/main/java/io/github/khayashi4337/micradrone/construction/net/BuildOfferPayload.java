package io.github.khayashi4337.micradrone.construction.net;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C: the server's answer to a plan upload (M2) - the offer table of {@code OfferView} written as
 * one JSON document. {@code state} is WORKING/OFFERED/REJECTED/FAILED; the client keeps the document
 * in {@code ClientBuildState} for the build screen.
 */
public record BuildOfferPayload(String offerJson) implements CustomPacketPayload {
    /**
     * The offer document carries one entry per plan issue, so its size follows the plan; the bound
     * keeps a corrupt stream from allocating unbounded while staying far above any real offer
     * (registered packets are split by NeoForge's GenericPacketSplitter anyway, S-6).
     */
    public static final int MAX_JSON_CHARS = 262_144;

    public static final Type<BuildOfferPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, "build_offer"));
    public static final StreamCodec<ByteBuf, BuildOfferPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_JSON_CHARS), BuildOfferPayload::offerJson,
            BuildOfferPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
