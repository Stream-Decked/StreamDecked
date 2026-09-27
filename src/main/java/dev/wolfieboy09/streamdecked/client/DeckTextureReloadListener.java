package dev.wolfieboy09.streamdecked.client;

import dev.wolfieboy09.streamdecked.StreamDecked;
import dev.wolfieboy09.streamdecked.api.NothingNullByDefault;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@NothingNullByDefault
public final class DeckTextureReloadListener implements PreparableReloadListener {
    @Override
    public CompletableFuture<Void> reload(SharedState sharedState, Executor executor, PreparationBarrier barrier, Executor executor1) {
        return barrier.wait(new Void[] {})
                .thenRunAsync(() -> {
                    DeckTextures.invalidate();
                    StreamDeckDriver.rerenderAll();
                    StreamDecked.LOGGER.debug("Cleared Stream Deck texture cache after a resource reload");
                }, executor);
    }
}
