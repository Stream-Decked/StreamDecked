package dev.wolfieboy09.streamdecked.client;

import dev.wolfieboy09.streamdecked.StreamDecked;
import dev.wolfieboy09.streamdecked.StreamDeckedMixinDetection;
import dev.wolfieboy09.sd5j.core.DeckButton;
import dev.wolfieboy09.sd5j.core.DeckEvent;
import dev.wolfieboy09.sd5j.core.DeckModel;
import dev.wolfieboy09.sd5j.core.DeckSurface;
import dev.wolfieboy09.sd5j.core.DeckText;
import dev.wolfieboy09.sd5j.core.StreamDeckManager;
import dev.wolfieboy09.sd5j.remote.RemoteDeckTransport;
import dev.wolfieboy09.sd5j.core.image.DeckImage;
import dev.wolfieboy09.streamdecked.event.DeckInputEvent;
import dev.wolfieboy09.streamdecked.event.DeckLifecycleEvent;
import dev.wolfieboy09.streamdecked.event.StreamDeckSetupEvent;
import dev.wolfieboy09.streamdecked.plugin.DeckLayoutRegistry;
import dev.wolfieboy09.streamdecked.plugin.StreamDeckedPlugin;
import dev.wolfieboy09.streamdecked.plugin.StreamDeckedPluginLoader;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static driver for the connected decks. Groups registered layouts by namespace into
 * folders on the deck's root; addons just populate their folder's content via
 * {@link StreamDeckedPlugin}.
 */
@SuppressWarnings("unused")
public final class StreamDeckDriver {
    private StreamDeckDriver() {}

    private static final Map<String, DeckSurface> SURFACES = new ConcurrentHashMap<>();

    private static volatile StreamDeckManager manager;
    private static volatile List<DeckLayoutRegistry.Entry> layouts = List.of();

    public static synchronized StreamDeckManager install() {
        if (manager != null) return manager;
        return doInstall();
    }

    private static StreamDeckManager doInstall() {
        StreamDeckManager created = new StreamDeckManager(new RemoteDeckTransport());
        created.addErrorHandler(t -> StreamDecked.LOGGER.error("Stream Deck driver error", t));

        StreamDeckSetupEvent setup = new StreamDeckSetupEvent(created);
        ModLoader.postEvent(setup);
        created.setDefaultBrightness(setup.getDefaultBrightness());
        created.setResetOnConnect(setup.isResetOnConnect());

        layouts = registerAll();
        StreamDecked.LOGGER.info("StreamDecked starting with {} registered layout(s)", layouts.size());

        manager = created;
        created.start();

        StreamDeckedMixinDetection.preach();

        NeoForge.EVENT_BUS.addListener(StreamDeckDriver::onClientTick);
        Runtime.getRuntime().addShutdownHook(new Thread(StreamDeckDriver::shutdown, "stream-deck-shutdown"));
        return created;
    }

    public static StreamDeckManager manager() {
        StreamDeckManager m = manager;
        if (m == null) throw new IllegalStateException("StreamDeckDriver.install() has not run yet");
        return m;
    }

    public static boolean isInstalled() { return manager != null; }

    /** Returns every deck's display to its idle logo. No-op if the driver has not started. */
    public static void resetAllDecks() {
        if (isInstalled()) manager().resetAllDecks();
    }

    /** Surfaces for every deck currently plugged in. */
    public static List<DeckSurface> surfaces() { return List.copyOf(SURFACES.values()); }

    public static DeckSurface surface(String deckId) { return SURFACES.get(deckId); }

    /** Layouts registered at load time, in the order they are applied. */
    public static List<DeckLayoutRegistry.Entry> layouts() { return layouts; }

    /** Asks every plugin to register its layouts and returns the combined entries. */
    private static List<DeckLayoutRegistry.Entry> registerAll() {
        DeckLayoutRegistry registry = new DeckLayoutRegistry();
        for (StreamDeckedPlugin plugin : StreamDeckedPluginLoader.discover()) {
            try {
                plugin.registerLayouts(registry);
            } catch (Throwable t) {
                StreamDecked.LOGGER.error("Stream Deck plugin {} failed to register", plugin.getClass().getName(), t);
            }
        }
        return registry.getEntries();
    }

    /** Re-renders and re-uploads every key of every surface, without touching navigation state. */
    public static void rerenderAll() {
        for (DeckSurface surface : SURFACES.values()) {
            surface.redrawAll();
        }
    }

    public static synchronized void shutdown() {
        StreamDeckManager m = manager;
        if (m == null) return;
        StreamDecked.LOGGER.info("Shutting down Stream Deck driver");
        manager = null;
        SURFACES.clear();
        m.close();
    }

    // ------------------------------------------------------------------

    private static void onClientTick(ClientTickEvent.Post event) {
        StreamDeckManager m = manager;
        if (m == null) return;
        m.drainEvents(StreamDeckDriver::route);
    }

    private static void route(DeckEvent event) {
        if (event instanceof DeckEvent.Connected connected) {
            onConnected(connected);
            return;
        }
        if (event instanceof DeckEvent.Disconnected(String deckId, DeckModel model)) {
            DeckSurface surface = SURFACES.remove(deckId);
            StreamDecked.LOGGER.info("Stream Deck disconnected: {} ({})", deckId, model);
            if (surface != null) {
                NeoForge.EVENT_BUS.post(new DeckLifecycleEvent.Disconnected(surface));
            }
            return;
        }

        DeckSurface surface = SURFACES.get(event.deckId());
        if (surface == null) return;

        DeckInputEvent busEvent = toBusEvent(surface, event);
        if (busEvent != null && NeoForge.EVENT_BUS.post(busEvent).isCanceled()) {
            return;
        }
        surface.handle(event);
    }

    private static void onConnected(DeckEvent.Connected connected) {
        DeckSurface surface = new DeckSurface(manager(), connected.deckId(), connected.model());
        SURFACES.put(connected.deckId(), surface);
        StreamDecked.LOGGER.info("Stream Deck connected: {} ({}, {} keys)",
                connected.deckId(), connected.model(), connected.model().keyCount());

        DeckLifecycleEvent.Connected lifecycle = new DeckLifecycleEvent.Connected(surface);
        if (NeoForge.EVENT_BUS.post(lifecycle).isCanceled()) {
            StreamDecked.LOGGER.debug("Layout application cancelled for deck {}", connected.deckId());
            return;
        }

        applyLayouts(surface);
        NeoForge.EVENT_BUS.post(new DeckLifecycleEvent.Ready(surface));
    }

    /** Groups registered layouts by namespace, captures each into a folder, and mounts them under a home button on the root. */
    private static void applyLayouts(DeckSurface surface) {
        DeckModel model = surface.model();
        if (!model.hasKeyScreens()) {
            StreamDecked.LOGGER.debug("{} has no key screens; skipping layout auto-organization", model);
            return;
        }

        Map<String, List<DeckLayoutRegistry.Entry>> byNamespace = new LinkedHashMap<>();
        for (DeckLayoutRegistry.Entry entry : layouts) {
            byNamespace.computeIfAbsent(entry.id().getNamespace(), namespace -> new ArrayList<>()).add(entry);
        }

        List<DeckButton> folderButtons = new ArrayList<>();
        for (Map.Entry<String, List<DeckLayoutRegistry.Entry>> group : byNamespace.entrySet()) {
            DeckButton folderButton = buildFolderButton(surface, group.getKey(), group.getValue());
            if (folderButton != null) folderButtons.add(folderButton);
        }
        if (folderButtons.isEmpty()) return;

        DeckModel.ImageSpec spec = model.keyImage();
        DeckButton home = DeckButton.folder(
                DeckText.label(spec.width(), spec.height(), "Decked Out", 0xFFFFFFFF, 0xFF2D3138),
                buildModListPages(folderButtons, model));
        Map<Integer, DeckButton> root = new HashMap<>();
        root.put(0, home);
        int exitKey = model.keyCount() - 1;
        if (exitKey != 0) {
            root.put(exitKey, DeckButton.text("Exit", 0xFFFFFFFF, 0xFF7A2020, StreamDeckDriver::exitModspace));
        }
        surface.setRootPage(root);
    }

    /**
     * Leaves Modspace: the plugin restores the deck's previous profile, handing it back to
     * whatever the user was using before the mod took over.
     */
    public static void exitModspace() {
        if (!isInstalled()) return;
        manager().exitModspace();
    }

    /** Concatenates a namespace's layouts into one mod folder of layout folders. Null if every entry opted out or failed. */
    @Nullable
    private static DeckButton buildFolderButton(DeckSurface surface, String namespace,
                                                 List<DeckLayoutRegistry.Entry> entries) {
        List<DeckButton> layoutFolders = new ArrayList<>();
        DeckImage icon = null;
        DeckModel.ImageSpec spec = surface.model().keyImage();

        for (DeckLayoutRegistry.Entry entry : entries) {
            try {
                DeckSurface capture = new DeckSurface(manager(), "capture:" + entry.id(), surface.model());
                if (!entry.layout().appliesTo(capture)) continue;

                entry.layout().populate(capture);
                List<Map<Integer, DeckButton>> pages = capture.exportPages();
                injectFolderNavigation(pages, surface.model());

                if (icon == null) icon = entry.icon();
                layoutFolders.add(DeckButton.folder(
                        DeckText.label(spec.width(), spec.height(), entry.id().getPath(), 0xFFFFFFFF, 0xFF2D3138),
                        pages));
            } catch (Throwable t) {
                StreamDecked.LOGGER.error("Deck layout {} failed on {}", entry.id(), surface.deckId(), t);
            }
        }

        if (layoutFolders.isEmpty() || icon == null) return null;
        return DeckButton.folder(icon, buildModListPages(layoutFolders, surface.model()));
    }

    /** Adds back/next/previous navigation to every page, in reserved bottom-row slots. */
    private static void injectFolderNavigation(List<Map<Integer, DeckButton>> pages, DeckModel model) {
        int keyCount = model.keyCount();
        int backKey = keyCount - model.columns();
        boolean paginated = pages.size() > 1;
        int prevKey = backKey + 1;
        int nextKey = keyCount - 1;

        for (Map<Integer, DeckButton> page : pages) {
            if (page.containsKey(backKey)) {
                throw new IllegalStateException("key " + backKey + " is reserved for the folder's back button");
            }
            if (paginated && page.containsKey(nextKey)) {
                throw new IllegalStateException("key " + nextKey + " is reserved for the next-page button "
                        + "(this folder has " + pages.size() + " pages)");
            }
            if (paginated && page.containsKey(prevKey)) {
                throw new IllegalStateException("key " + prevKey + " is reserved for the previous-page button "
                        + "(this folder has " + pages.size() + " pages)");
            }
        }

        for (int i = 0; i < pages.size(); i++) {
            Map<Integer, DeckButton> page = new HashMap<>(pages.get(i));
            page.put(backKey, DeckButton.back("Back", 0xFFFFFFFF, 0xFF202020));
            if (paginated) {
                page.put(nextKey, DeckButton.nextPage("Next", 0xFFFFFFFF, 0xFF202020));
                page.put(prevKey, DeckButton.previousPage("Prev", 0xFFFFFFFF, 0xFF202020));
            }
            pages.set(i, page);
        }
    }

    /** Lays the registered mod folder buttons out across one or more pages, with back/next/previous navigation. */
    private static List<Map<Integer, DeckButton>> buildModListPages(List<DeckButton> folders, DeckModel model) {
        int keyCount = model.keyCount();
        int backKey = keyCount - model.columns();
        int prevKey = backKey + 1;
        int nextKey = keyCount - 1;
        boolean paginated = folders.size() > Math.max(0, keyCount - 1);

        List<Integer> contentKeys = new ArrayList<>();
        for (int k = 0; k < keyCount; k++) {
            if (k == backKey || (paginated && (k == prevKey || k == nextKey))) continue;
            contentKeys.add(k);
        }
        if (contentKeys.isEmpty()) return List.of(Map.of());

        List<Map<Integer, DeckButton>> pages = new ArrayList<>();
        for (int start = 0; start < folders.size(); start += contentKeys.size()) {
            int end = Math.min(start + contentKeys.size(), folders.size());
            Map<Integer, DeckButton> page = new HashMap<>();
            for (int i = start; i < end; i++) page.put(contentKeys.get(i - start), folders.get(i));
            pages.add(page);
        }
        injectFolderNavigation(pages, model);
        return pages;
    }

    @Nullable
    private static DeckInputEvent toBusEvent(DeckSurface surface, DeckEvent event) {
        if (event instanceof DeckEvent.KeyDown e) {
            return new DeckInputEvent.Key(surface, event, e.key(), true);
        }
        if (event instanceof DeckEvent.KeyUp e) {
            return new DeckInputEvent.Key(surface, event, e.key(), false);
        }
        if (event instanceof DeckEvent.TouchPointDown e) {
            return new DeckInputEvent.TouchPoint(surface, event, e.point(), true);
        }
        if (event instanceof DeckEvent.TouchPointUp e) {
            return new DeckInputEvent.TouchPoint(surface, event, e.point(), false);
        }
        if (event instanceof DeckEvent.EncoderDown e) {
            return DeckInputEvent.Encoder.pushed(surface, event, e.encoder(), true);
        }
        if (event instanceof DeckEvent.EncoderUp e) {
            return DeckInputEvent.Encoder.pushed(surface, event, e.encoder(), false);
        }
        if (event instanceof DeckEvent.EncoderTurn e) {
            return DeckInputEvent.Encoder.turned(surface, event, e.encoder(), e.delta());
        }
        if (event instanceof DeckEvent.ScreenTap e) {
            return new DeckInputEvent.Screen(surface, event,
                    DeckInputEvent.Screen.Kind.TAP, e.x(), e.y(), e.x(), e.y());
        }
        if (event instanceof DeckEvent.ScreenHold e) {
            return new DeckInputEvent.Screen(surface, event,
                    DeckInputEvent.Screen.Kind.HOLD, e.x(), e.y(), e.x(), e.y());
        }
        if (event instanceof DeckEvent.ScreenSwipe e) {
            return new DeckInputEvent.Screen(surface, event,
                    DeckInputEvent.Screen.Kind.SWIPE, e.fromX(), e.fromY(), e.toX(), e.toY());
        }
        return null;
    }
}