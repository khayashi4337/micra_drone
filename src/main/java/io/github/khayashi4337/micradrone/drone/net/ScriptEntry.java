package io.github.khayashi4337.micradrone.drone.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One selectable script in a controller's list (issue #6, chest library): {@code id} is what the
 * client sends back to run/edit/save it (a {@code *.mdrone} file name, or a {@code scroll:c:s}
 * chest-scroll id - see ScriptId), {@code displayName} is what the list shows (for scrolls: the
 * item's hover name, so an anvil rename shows up here), {@code description} is the script's
 * leading {@code #} comment, and {@code isNew} marks a blank scroll sitting in the library (林さん's
 * request: a blank scroll should be pickable straight from the list as a "start writing here"
 * entry, instead of being invisible until it already has content - see
 * {@code ScriptChestLibrary#listScrolls}).
 */
public record ScriptEntry(String id, String displayName, String description, boolean isNew) {
    public static final StreamCodec<ByteBuf, ScriptEntry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ScriptEntry::id,
            ByteBufCodecs.STRING_UTF8, ScriptEntry::displayName,
            ByteBufCodecs.STRING_UTF8, ScriptEntry::description,
            ByteBufCodecs.BOOL, ScriptEntry::isNew,
            ScriptEntry::new);
}
