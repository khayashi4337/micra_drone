package io.github.khayashi4337.micradrone.construction;

import io.github.khayashi4337.micradrone.MicraDrone;
import io.github.khayashi4337.micradrone.build.compile.PalettePolicy;
import io.github.khayashi4337.micradrone.build.compile.PlaceableBlockPolicy;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * The datapack tags the construction runtime consults (D-22): the natural ground terraform may cut or fill, and the
 * blocks a plan may place. The palette tag's contents replace the built-in allow list; what an absent or empty tag
 * means is the pure decision of build.compile.PalettePolicy.
 */
public final class BuildTags {
    public static final TagKey<Block> TERRAFORMABLE = tag("terraformable");
    public static final TagKey<Block> PALETTE_ALLOWED = tag("palette_allowed");

    private BuildTags() {
    }

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(MicraDrone.MODID, name));
    }

    /**
     * The placement policy as the datapack defines it (D-22): a present tag wins even when empty - a server
     * administrator may forbid everything on purpose. Only a missing tag falls back to the built-in table, which
     * BuildTagFilesTest keeps identical to the shipped file, so an absent datapack never allows more.
     */
    public static PlaceableBlockPolicy policy() {
        Optional<HolderSet.Named<Block>> tag = BuiltInRegistries.BLOCK.getTag(PALETTE_ALLOWED);
        if (tag.isEmpty()) {
            MicraDrone.LOGGER.warn("datapack tag {} is absent; the built-in allow list applies",
                    PALETTE_ALLOWED.location());
            return PalettePolicy.from(Optional.empty());
        }
        Set<String> ids = new TreeSet<>();
        for (Holder<Block> holder : tag.get()) {
            ids.add(BuiltInRegistries.BLOCK.getKey(holder.value()).toString());
        }
        return PalettePolicy.from(Optional.of(ids));
    }
}
