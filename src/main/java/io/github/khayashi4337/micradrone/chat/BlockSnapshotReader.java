package io.github.khayashi4337.micradrone.chat;

import java.util.Optional;

/**
 * The actual block-reading behind get_block_snapshot. Separated from the MCP/HTTP plumbing
 * (McpProtocol, BlockSnapshotToolServer) so those stay unit-testable with a fake reader, and the
 * real Minecraft-touching implementation (T-7b: ClientMainThreadDispatch) is the only piece that
 * needs a running game to exercise.
 */
public interface BlockSnapshotReader {
    /**
     * A human-readable description of the blocks in the (inclusive) range, or empty if any part
     * of the range isn't currently loaded on the client.
     */
    Optional<String> read(int x1, int y1, int z1, int x2, int y2, int z2);
}
