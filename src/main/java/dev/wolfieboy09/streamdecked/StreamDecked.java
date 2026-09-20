package dev.wolfieboy09.streamdecked;

import com.mojang.logging.LogUtils;
import dev.wolfieboy09.streamdecked.client.DeckTextureReloadListener;
import dev.wolfieboy09.streamdecked.client.StreamDeckDriver;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;

@Mod(StreamDecked.MOD_ID)
public class StreamDecked {
    public static final String MOD_ID = "streamdecked";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StreamDecked(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::onClientReload);

        NeoForge.EVENT_BUS.addListener(this::clientShutdown);
        NeoForge.EVENT_BUS.addListener(this::clientLeaveWorld);
        NeoForge.EVENT_BUS.addListener(this::clientJoinWorld);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(StreamDeckDriver::install);
    }

    private void onClientReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new DeckTextureReloadListener());
    }

    private void clientShutdown(GameShuttingDownEvent event) {
        if (StreamDeckDriver.isInstalled()) {
            StreamDeckDriver.shutdown();
        }
    }

    private void clientLeaveWorld(ClientPlayerNetworkEvent.LoggingOut event) {
        StreamDeckDriver.resetAllDecks();
    }

    private void clientJoinWorld(ClientPlayerNetworkEvent.LoggingIn event) {
        StreamDeckDriver.rerenderAll();
    }
}
