package io.github.khayashi4337.micradrone.client;

import io.github.khayashi4337.micradrone.chat.MainThreadExecutor;
import net.minecraft.client.Minecraft;

/**
 * The real MainThreadExecutor: {@code Minecraft.execute(Runnable)} is the client-side counterpart
 * of {@code MinecraftServer.execute(Runnable)} (both extend BlockableEventLoop, backed by a
 * thread-safe queue drained once per frame - verified in decompiled sources before writing this).
 */
public final class MinecraftMainThreadExecutor implements MainThreadExecutor {
    public static final MinecraftMainThreadExecutor INSTANCE = new MinecraftMainThreadExecutor();

    private MinecraftMainThreadExecutor() {
    }

    @Override
    public void execute(Runnable task) {
        Minecraft.getInstance().execute(task);
    }
}
