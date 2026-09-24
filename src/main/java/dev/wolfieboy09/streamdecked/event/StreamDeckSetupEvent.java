package dev.wolfieboy09.streamdecked.event;

import dev.wolfieboy09.sd5j.core.StreamDeckManager;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;
import dev.wolfieboy09.streamdecked.plugin.StreamDeckedPlugin;

/**
 * Fired once on the <b>mod event bus</b> right after the driver thread starts, before any deck
 * opens. Use to adjust driver-wide settings; addons that only want to put buttons on a panel
 * should implement {@link StreamDeckedPlugin} instead.
 */
@SuppressWarnings("unused")
public class StreamDeckSetupEvent extends Event implements IModBusEvent {

    private final StreamDeckManager manager;
    private int defaultBrightness = 80;
    private boolean resetOnConnect = true;

    public StreamDeckSetupEvent(StreamDeckManager manager) {
        this.manager = manager;
    }

    /** The live driver. Queue work on it if you need something outside the layout system. */
    public StreamDeckManager getManager() {
        return manager;
    }

    /**
     * Brightness applied to each deck as it connects, 0 to 100. Last listener to set it wins,
     * so treat this as a default rather than a user setting.
     */
    public void setDefaultBrightness(int percent) {
        this.defaultBrightness = Math.clamp(percent, 0, 100);
    }

    public int getDefaultBrightness() {
        return defaultBrightness;
    }

    /** Whether a connecting deck is reset and blanked before layouts run. On by default. */
    public void setResetOnConnect(boolean reset) {
        this.resetOnConnect = reset;
    }

    public boolean isResetOnConnect() {
        return resetOnConnect;
    }
}