package dev.wolfieboy09.streamdecked.core;

import com.mojang.logging.LogUtils;
import dev.wolfieboy09.streamdecked.core.hid.HidBackend;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the driver thread and every open deck. HID reads and JPEG encoding are not free, so none
 * of it runs on the game thread: hand in work with {@link #submit} and collect input with
 * {@link #drainEvents}, both safe from any thread.
 *
 * <pre>{@code
 * StreamDeckManager manager = new StreamDeckManager(new Hid4JavaBackend());
 * manager.setDefaultBrightness(70);
 * manager.start();
 *
 * manager.submitAll(deck -> deck.setKeyImage(0, icon));
 *
 * // once per client tick:
 * manager.drainEvents(event -> { ... });
 * }</pre>
 */
@SuppressWarnings("unused")
public final class StreamDeckManager implements AutoCloseable {
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Work to run against one open deck, on the driver thread. */
    @FunctionalInterface
    public interface DeckTask {
        void run(StreamDeck deck) throws Exception;
    }

    /** Identity of a connected deck, safe to hold onto from any thread. */
    public record DeckInfo(String id, DeckModel model) {}

    private static final int SCAN_INTERVAL_MS = 2000;
    private static final int READ_TIMEOUT_MS = 10;
    /** Elgato hardware puts its control interface on this usage page. */
    private static final int USAGE_PAGE_CONSUMER = 0x000C;

    private final HidBackend backend;
    private final Queue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final Queue<DeckEvent> events = new ConcurrentLinkedQueue<>();
    private final List<Consumer<DeckEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> errorHandlers = new CopyOnWriteArrayList<>();

    // driver thread only
    private final Map<String, StreamDeck> decks = new LinkedHashMap<>();
    private final Map<String, boolean[]> buttonStates = new HashMap<>();
    private final Map<String, boolean[]> encoderStates = new HashMap<>();
    private final Set<String> loggedSkips = new HashSet<>();

    private volatile List<DeckInfo> connected = List.of();
    private volatile Thread thread;
    private volatile boolean running;
    private volatile int defaultBrightness = 80;
    private volatile boolean resetOnConnect = true;
    /** Empty set means "no restriction, claim every attached Elgato device" (the default). */
    private volatile Set<String> allowedDeckIds = Set.of();

    public StreamDeckManager(HidBackend backend) {
        this.backend = backend;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public synchronized void start() {
        if (running) return;
        running = true;
        warnIfElgatoSoftwareRunning();
        Thread t = new Thread(this::runLoop, "stream-deck-driver");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /**
     * Warns if the official Elgato Stream Deck app is running. hidapi always opens HID devices in
     * shared mode, so this driver cannot take exclusive ownership of a deck: the official app will
     * repaint over images set here and fire its own action on every press. Closing it is the only
     * fix; this check surfaces that as a clear log line instead of a confusing bug report.
     */
    private void warnIfElgatoSoftwareRunning() {
        try {
            boolean found = ProcessHandle.allProcesses()
                    .map(ProcessHandle::info)
                    .map(info -> info.command().orElse(""))
                    .map(cmd -> cmd.toLowerCase(Locale.ROOT))
                    .anyMatch(cmd -> cmd.contains("streamdeck") || cmd.contains("stream deck"));
            if (found) {
                LOGGER.warn("The official Elgato Stream Deck software appears to be running. HID access is "
                        + "shared, not exclusive, so any deck this driver claims will conflict with it: "
                        + "the official app will repaint over images this driver sets and will also fire "
                        + "its own action on every press, alongside this mod's. Close the Elgato Stream "
                        + "Deck app before using a claimed deck. This is a known limitation of the "
                        + "underlying HID library, not a bug.");
            }
        } catch (Throwable t) {
            // Best effort only. Process enumeration can be restricted on some platforms/sandboxes.
            // Never let this stop the driver from starting.
            LOGGER.debug("Could not check for a running Elgato Stream Deck process", t);
        }
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        thread = null;
    }

    public boolean isRunning() { return running; }

    /** Brightness applied to decks as they connect, 0 to 100. */
    public void setDefaultBrightness(int percent) { this.defaultBrightness = percent; }

    /** Whether a newly connected deck is reset and blanked. On by default. */
    public void setResetOnConnect(boolean reset) { this.resetOnConnect = reset; }

    /**
     * Restricts the driver to the given deck ids (serials, as in {@link DeckInfo#id()}). Empty or
     * null removes the restriction (the default). Excluded decks are never opened or blanked, so
     * the official Elgato software keeps normal access to them. A claimed deck still shares input
     * with the official app (hidapi is shared mode); quit it to avoid duplicate actions.
     */
    public void setAllowedDeckIds(Collection<String> ids) {
        this.allowedDeckIds = (ids == null || ids.isEmpty()) ? Set.of() : Set.copyOf(ids);
    }

    /** Current restriction, empty meaning "no restriction". */
    public Set<String> getAllowedDeckIds() { return allowedDeckIds; }

    // ------------------------------------------------------------------
    // Public API, callable from any thread
    // ------------------------------------------------------------------

    /** Decks currently open, newest last. */
    public List<DeckInfo> connectedDecks() { return connected; }

    public boolean hasDeck() { return !connected.isEmpty(); }

    /** Queues work against one deck. Silently dropped if that deck is gone by the time it runs. */
    public void submit(String deckId, DeckTask task) {
        commands.add(() -> {
            StreamDeck deck = decks.get(deckId);
            if (deck != null) runTask(deck, task);
        });
    }

    /** Queues work against every connected deck. */
    public void submitAll(DeckTask task) {
        commands.add(() -> {
            for (StreamDeck deck : new ArrayList<>(decks.values())) runTask(deck, task);
        });
    }

    /**
     * Returns every connected deck's display to its idle logo, without touching button
     * assignments or the driver thread itself. Use when leaving a world or otherwise wanting
     * the panel blanked without a full shutdown.
     */
    public void resetAllDecks() {
        submitAll(StreamDeck::reset);
    }

    /** Queues work that needs no deck, run in order with the rest. */
    public void submit(Runnable work) {
        commands.add(work);
    }

    /**
     * Hands every queued event to {@code consumer} on the calling thread and clears the queue.
     * Registered listeners fire too. Call this once per client tick.
     */
    public void drainEvents(Consumer<DeckEvent> consumer) {
        DeckEvent event;
        while ((event = events.poll()) != null) {
            if (consumer != null) {
                try { consumer.accept(event); } catch (Throwable t) { reportError(t); }
            }
            for (Consumer<DeckEvent> listener : listeners) {
                try { listener.accept(event); } catch (Throwable t) { reportError(t); }
            }
        }
    }

    public void drainEvents() { drainEvents(null); }

    /** Listeners fire on whichever thread calls {@link #drainEvents}. */
    public void addListener(Consumer<DeckEvent> listener) { listeners.add(listener); }

    public void removeListener(Consumer<DeckEvent> listener) { listeners.remove(listener); }

    /** Driver thread failures are routed here instead of killing the thread. */
    public void addErrorHandler(Consumer<Throwable> handler) { errorHandlers.add(handler); }

    // ------------------------------------------------------------------
    // Driver thread
    // ------------------------------------------------------------------

    private void runLoop() {
        try { DeckText.warmup(); } catch (Throwable t) { LOGGER.debug("AWT warm-up failed", t); }
        long lastScan = 0;
        try {
            while (running) {
                long now = System.currentTimeMillis();
                if (now - lastScan >= SCAN_INTERVAL_MS) {
                    lastScan = now;
                    try { scan(); } catch (Throwable t) { reportError(t); }
                }

                Runnable command;
                while ((command = commands.poll()) != null) {
                    try { command.run(); } catch (Throwable t) { reportError(t); }
                }

                if (decks.isEmpty()) {
                    sleep(100);
                    continue;
                }
                pollInput();
            }
        } finally {
            for (StreamDeck deck : new ArrayList<>(decks.values())) {
                try { deck.reset(); } catch (Throwable ignored) { }
                try { deck.close(); } catch (Throwable ignored) { }
            }
            decks.clear();
            buttonStates.clear();
            encoderStates.clear();
            loggedSkips.clear();
            connected = List.of();
            commands.clear();
            try { backend.close(); } catch (Throwable ignored) { }
        }
    }

    private void scan() {
        List<HidBackend.HidDeviceRef> refs = backend.enumerate(DeckModel.ELGATO_VENDOR_ID);

        Map<String, HidBackend.HidDeviceRef> found = createFound(refs);
        Set<String> allowed = allowedDeckIds;

        for (String gone : new ArrayList<>(decks.keySet())) {
            if (found.containsKey(gone)) continue;
            StreamDeck deck = decks.remove(gone);
            buttonStates.remove(gone);
            encoderStates.remove(gone);
            loggedSkips.remove(gone);
            if (deck != null) {
                try { deck.close(); } catch (Throwable ignored) { }
                events.add(new DeckEvent.Disconnected(gone, deck.model()));
            }
        }

        for (Map.Entry<String, HidBackend.HidDeviceRef> entry : found.entrySet()) {
            String id = entry.getKey();
            if (decks.containsKey(id)) continue;

            if (!allowed.isEmpty() && !allowed.contains(id)) {
                if (loggedSkips.add(id)) {
                    DeckModel model = DeckModel.fromUsbIds(entry.getValue().vendorId(), entry.getValue().productId());
                    LOGGER.info("Stream Deck {} ({}) found but not in the allowed deck list, leaving it alone. "
                            + "Add \"{}\" via setAllowedDeckIds(...) to let this mod claim it.", id, model, id);
                }
                continue;
            }

            HidBackend.HidDeviceRef ref = entry.getValue();
            DeckModel model = DeckModel.fromUsbIds(ref.vendorId(), ref.productId());
            try {
                StreamDeck deck = new StreamDeck(model, backend.open(ref), id);
                if (resetOnConnect) {
                    deck.reset();
                    deck.setBrightness(defaultBrightness);
                    deck.clearAllKeys();
                }
                decks.put(id, deck);
                buttonStates.put(id, new boolean[model.buttonStateCount()]);
                encoderStates.put(id, new boolean[model.encoderCount()]);
                events.add(new DeckEvent.Connected(id, model));
            } catch (Throwable t) {
                reportError(t);
            }
        }
        refreshConnectedList();
    }

    private static @NotNull Map<String, HidBackend.HidDeviceRef> createFound(List<HidBackend.HidDeviceRef> refs) {
        Map<String, HidBackend.HidDeviceRef> found = new LinkedHashMap<>();
        for (HidBackend.HidDeviceRef ref : refs) {
            DeckModel model = DeckModel.fromUsbIds(ref.vendorId(), ref.productId());
            if (model == null) continue;
            // Some models expose extra interfaces; the control one is on the consumer page.
            // A usage page of 0 means the OS did not tell us, so take it and hope.
            if (ref.usagePage() != 0 && ref.usagePage() != USAGE_PAGE_CONSUMER) continue;
            found.putIfAbsent(ref.identity(), ref);
        }
        return found;
    }

    private void refreshConnectedList() {
        List<DeckInfo> list = new ArrayList<>(decks.size());
        for (Map.Entry<String, StreamDeck> e : decks.entrySet()) {
            list.add(new DeckInfo(e.getKey(), e.getValue().model()));
        }
        connected = List.copyOf(list);
    }

    private void pollInput() {
        for (Map.Entry<String, StreamDeck> entry : new ArrayList<>(decks.entrySet())) {
            String id = entry.getKey();
            StreamDeck deck = entry.getValue();
            try {
                // Drain what is pending, but never let one chatty deck hold the loop.
                for (int i = 0; i < 16; i++) {
                    DeckInput input = deck.read(i == 0 ? READ_TIMEOUT_MS : 0);
                    if (input instanceof DeckInput.None) break;
                    handleInput(id, deck.model(), input);
                }
            } catch (Throwable t) {
                reportError(t);
                decks.remove(id);
                buttonStates.remove(id);
                encoderStates.remove(id);
                try { deck.close(); } catch (Throwable ignored) { }
                events.add(new DeckEvent.Disconnected(id, deck.model()));
                refreshConnectedList();
            }
        }
    }

    private void handleInput(String id, DeckModel model, DeckInput input) {
        if (input instanceof DeckInput.Buttons(boolean[] now)) {
            boolean[] previous = buttonStates.get(id);
            if (previous == null) return;
            int keyCount = model.keyCount();
            for (int i = 0; i < Math.min(previous.length, now.length); i++) {
                if (previous[i] == now[i]) continue;
                if (i < keyCount) {
                    events.add(now[i] ? new DeckEvent.KeyDown(id, model, i)
                            : new DeckEvent.KeyUp(id, model, i));
                } else {
                    int point = i - keyCount;
                    events.add(now[i] ? new DeckEvent.TouchPointDown(id, model, point)
                            : new DeckEvent.TouchPointUp(id, model, point));
                }
            }
            buttonStates.put(id, now);

        } else if (input instanceof DeckInput.EncoderPress(boolean[] now)) {
            boolean[] previous = encoderStates.get(id);
            if (previous == null) return;
            for (int i = 0; i < Math.min(previous.length, now.length); i++) {
                if (previous[i] == now[i]) continue;
                events.add(now[i] ? new DeckEvent.EncoderDown(id, model, i)
                        : new DeckEvent.EncoderUp(id, model, i));
            }
            encoderStates.put(id, now);

        } else if (input instanceof DeckInput.EncoderTurn(int[] deltas)) {
            for (int i = 0; i < deltas.length; i++) {
                if (deltas[i] != 0) events.add(new DeckEvent.EncoderTurn(id, model, i, deltas[i]));
            }

        } else if (input instanceof DeckInput.TouchTap(int x, int y)) {
            events.add(new DeckEvent.ScreenTap(id, model, x, y));

        } else if (input instanceof DeckInput.TouchHold(int x, int y)) {
            events.add(new DeckEvent.ScreenHold(id, model, x, y));

        } else if (input instanceof DeckInput.TouchSwipe(int fromX, int fromY, int toX, int toY)) {
            events.add(new DeckEvent.ScreenSwipe(id, model,
                    fromX, fromY, toX, toY));
        }
    }

    private void runTask(StreamDeck deck, DeckTask task) {
        try {
            task.run(deck);
        } catch (Throwable t) {
            reportError(t);
        }
    }

    private void reportError(Throwable t) {
        if (errorHandlers.isEmpty()) {
            LOGGER.error("Stream Deck driver error", t);
            return;
        }
        for (Consumer<Throwable> handler : errorHandlers) {
            try { handler.accept(t); } catch (Throwable suppressed) {
                LOGGER.error("Stream Deck error handler threw", suppressed);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}