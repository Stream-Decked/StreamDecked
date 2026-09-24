package dev.wolfieboy09.streamdecked.event;

import dev.wolfieboy09.sd5j.core.DeckModel;
import dev.wolfieboy09.sd5j.core.DeckSurface;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** Deck hotplug events on the <b>game event bus</b> ({@code NeoForge.EVENT_BUS}), client thread during the tick. */
@SuppressWarnings("unused")
public abstract class DeckLifecycleEvent extends Event {
    private final DeckSurface surface;

    private DeckLifecycleEvent(DeckSurface surface) {
        this.surface = surface;
    }

    public DeckSurface getSurface() { return surface; }
    public String getDeckId()       { return surface.deckId(); }
    public DeckModel getModel()     { return surface.model(); }

    /**
     * A deck was opened and a surface built for it, before registered layouts run.
     * Cancel to keep the layout system off this panel entirely.
     */
    public static class Connected extends DeckLifecycleEvent implements ICancellableEvent {
        public Connected(DeckSurface surface) { super(surface); }
    }

    /** Layouts have run and the panel is drawn. Good place for a deck-wide finishing touch. */
    public static class Ready extends DeckLifecycleEvent {
        public Ready(DeckSurface surface) { super(surface); }
    }

    /** The deck went away. The surface is already detached; do not queue work on it. */
    public static class Disconnected extends DeckLifecycleEvent {
        public Disconnected(DeckSurface surface) { super(surface); }
    }
}