package dev.wolfieboy09.streamdecked.client;

import dev.wolfieboy09.streamdecked.StreamDecked;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class DeckTextureReloadListener implements PreparableReloadListener {
    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager,
                                          ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        return barrier.wait(new Void[] {})
                .thenRunAsync(() -> {
                    DeckTextures.invalidate();
                    StreamDeckDriver.rerenderAll();
                    StreamDecked.LOGGER.debug("Cleared Stream Deck texture cache after a resource reload");
                }, gameExecutor);
    }
}
