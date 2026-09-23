package dev.wolfieboy09.streamdecked.event;

import dev.wolfieboy09.sd5j.core.DeckButton;
import dev.wolfieboy09.sd5j.core.DeckEvent;
import dev.wolfieboy09.sd5j.core.DeckModel;
import dev.wolfieboy09.sd5j.core.DeckSurface;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Hardware input, fired on the <b>game event bus</b> on the client thread during the tick,
 * before the assigned {@link DeckButton} sees it. Cancelling stops the button callback from
 * running (how a mod intercepts a key another mod owns, or swallows the panel in a modal).
 */
@SuppressWarnings("unused")
public abstract class DeckInputEvent extends Event implements ICancellableEvent {

    private final DeckSurface surface;
    private final DeckEvent source;

    protected DeckInputEvent(DeckSurface surface, DeckEvent source) {
        this.surface = surface;
        this.source = source;
    }

    public DeckSurface getSurface() { return surface; }
    public String getDeckId()       { return surface.deckId(); }
    public DeckModel getModel()     { return surface.model(); }

    /** The underlying driver event, if you want the raw form. */
    public DeckEvent getSource()    { return source; }

    /** A physical key going down or up. */
    public static class Key extends DeckInputEvent {
        private final int key;
        private final boolean pressed;

        public Key(DeckSurface surface, DeckEvent source, int key, boolean pressed) {
            super(surface, source);
            this.key = key;
            this.pressed = pressed;
        }

        /** Logical key index, row major from the top left. */
        public int getKey()        { return key; }
        public boolean isPressed() { return pressed; }
        public int getColumn()     { return getModel().columnOf(key); }
        public int getRow()        { return getModel().rowOf(key); }
    }

    /** A capacitive touch point on a Neo. */
    public static class TouchPoint extends DeckInputEvent {
        private final int point;
        private final boolean pressed;

        public TouchPoint(DeckSurface surface, DeckEvent source, int point, boolean pressed) {
            super(surface, source);
            this.point = point;
            this.pressed = pressed;
        }

        public int getPoint()      { return point; }
        public boolean isPressed() { return pressed; }
    }

    /** A rotary encoder on a Stream Deck +, either pushed or turned. */
    public static class Encoder extends DeckInputEvent {
        private final int encoder;
        private final int delta;
        private final Boolean pressed;

        private Encoder(DeckSurface surface, DeckEvent source, int encoder, int delta, Boolean pressed) {
            super(surface, source);
            this.encoder = encoder;
            this.delta = delta;
            this.pressed = pressed;
        }

        public static Encoder turned(DeckSurface surface, DeckEvent source, int encoder, int delta) {
            return new Encoder(surface, source, encoder, delta, null);
        }

        public static Encoder pushed(DeckSurface surface, DeckEvent source, int encoder, boolean pressed) {
            return new Encoder(surface, source, encoder, 0, pressed);
        }

        public int getEncoder() { return encoder; }

        /** Detents since the last event, negative for counter-clockwise. Zero for a push. */
        public int getDelta()   { return delta; }

        public boolean isTurn() { return pressed == null; }
        public boolean isPush() { return pressed != null; }

        /** Only meaningful when {@link #isPush()}. */
        public boolean isPressed() { return pressed != null && pressed; }
    }

    /** A tap, hold or swipe on the touch strip. Coordinates are in strip pixels. */
    public static class Screen extends DeckInputEvent {
        public enum Kind { TAP, HOLD, SWIPE }

        private final Kind kind;
        private final int x;
        private final int y;
        private final int toX;
        private final int toY;

        public Screen(DeckSurface surface, DeckEvent source, Kind kind, int x, int y, int toX, int toY) {
            super(surface, source);
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.toX = toX;
            this.toY = toY;
        }

        public Kind getKind() { return kind; }
        public int getX()     { return x; }
        public int getY()     { return y; }
        /** End of a swipe; equal to {@link #getX()} for taps and holds. */
        public int getToX()   { return toX; }
        public int getToY()   { return toY; }
    }
}