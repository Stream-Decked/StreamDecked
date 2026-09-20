package dev.wolfieboy09.streamdecked.core;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.wolfieboy09.streamdecked.StreamDecked;
import dev.wolfieboy09.streamdecked.core.image.DeckImage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A deck's keys as assignable slots with a page stack on top. Owning the panel this way keeps
 * redraws cheap: only changed slots are re-encoded and uploaded; unassigned keys draw black.
 * Safe to call from the game thread; rendering and uploading happen on the driver thread.
 */
@SuppressWarnings("unused")
@CanIgnoreReturnValue
public final class DeckSurface {
    private final StreamDeckManager manager;
    private final String deckId;
    private final DeckModel model;
    private final AtomicReferenceArray<DeckButton> buttons;

    public DeckSurface(StreamDeckManager manager, StreamDeckManager.DeckInfo info) {
        this(manager, info.id(), info.model());
    }

    public DeckSurface(StreamDeckManager manager, String deckId, DeckModel model) {
        this.manager = manager;
        this.deckId = deckId;
        this.model = model;
        this.buttons = new AtomicReferenceArray<>(model.keyCount());
    }

    public String deckId()   { return deckId; }
    public DeckModel model() { return model; }
    public int keyCount()    { return model.keyCount(); }

    public @Nullable DeckButton button(int key) {
        return (key < 0 || key >= buttons.length()) ? null : buttons.get(key);
    }

    /** Assigns a button and redraws that key. Pass null to blank it. */
    public void setButton(int key, DeckButton button) {
        if (key < 0 || key >= buttons.length()) throw new IllegalArgumentException("no key " + key);
        buttons.set(key, button);
        redraw(key);
    }

    /**
     * Assigns a button by grid position. Columns past the deck's width wrap onto the next row,
     * so a layout written for a bigger deck still keeps its buttons. A position that wraps off
     * the bottom of the deck is dropped with a warning, never thrown.
     */
    public void setButton(int column, int row, DeckButton button) {
        int width = model.columns();
        if (column >= width) {
            StreamDecked.LOGGER.warn("Stream Deck {} ({}) has no column {}; wrapping ({}, {}) onto column {} row {}",
                    deckId, model, column, column, row, column % width, row + column / width);
            row += column / width;
            column %= width;
        }
        if (column < 0 || row < 0 || row >= model.rows()) {
            StreamDecked.LOGGER.warn("Stream Deck {} ({}) has no button at column {} row {}; dropping it", deckId, model, column, row);
            return;
        }
        setButton(model.keyIndex(column, row), button);
    }

    public void clearButton(int key) { setButton(key, null); }

    public void clearAll() {
        for (int i = 0; i < buttons.length(); i++) buttons.set(i, null);
        redrawAll();
    }

    /** Re-renders one key. Call after a button's appearance changes. */
    public void redraw(int key) {
        DeckButton button = buttons.get(key);
        manager.submit(deckId, deck -> {
            if (button == null) {
                deck.clearKey(key);
            } else {
                DeckModel.ImageSpec spec = deck.model().keyImage();
                deck.setKeyImage(key, button.render(spec.width(), spec.height()));
            }
        });
    }

    public void redrawAll() {
        for (int i = 0; i < buttons.length(); i++) redraw(i);
    }

    public void setBrightness(int percent) {
        manager.submit(deckId, deck -> deck.setBrightness(percent));
    }

    /** Fills the LCD strip on a Plus or the info screen on a Neo. No-op elsewhere. */
    public void setScreenImage(DeckImage image) {
        if (!model.hasScreen()) return;
        manager.submit(deckId, deck -> deck.setScreenImage(image));
    }

    // ------------------------------------------------------------------
    // Folders and pages
    // ------------------------------------------------------------------
    //
    // A "folder" is a level you can descend into and come back from (back() returns to
    // wherever you were, including which page of the parent was showing). A "page" is one of
    // several sibling layouts *within* the current folder that nextPage()/previousPage() cycle
    // between, without changing the folder stack. The base/root level is itself just a folder
    // with nothing above it; back() at the root does nothing and returns false.

    private record Frame(List<Map<Integer, DeckButton>> pages, int pageIndex) {}

    private final Deque<Frame> folderStack = new ArrayDeque<>();
    private List<Map<Integer, DeckButton>> pages = List.of(Map.of());
    private int pageIndex = 0;

    /**
     * Descends into a folder with a single page. The current level (including whichever page of
     * it was showing) is remembered and restored by {@link #back()}.
     */
    public void openFolder(Map<Integer, DeckButton> page) {
        openFolder(List.of(page));
    }

    /**
     * Descends into a folder with several sibling pages, showing the first. Use
     * {@link #nextPage()} / {@link #previousPage()} to move between them once inside.
     */
    public void openFolder(List<Map<Integer, DeckButton>> newPages) {
        if (newPages == null || newPages.isEmpty()) {
            throw new IllegalArgumentException("a folder needs at least one page");
        }
        commitCurrentPage();
        folderStack.push(new Frame(pages, pageIndex));
        pages = List.copyOf(newPages);
        pageIndex = 0;
        applyCurrentPage();
    }

    /**
     * Leaves the current folder, restoring the parent level exactly as it was (including which
     * of its pages was showing). Returns false if already at the root, in which case nothing
     * happens.
     */
    public boolean back() {
        Frame parent = folderStack.poll();
        if (parent == null) return false;
        pages = parent.pages();
        pageIndex = parent.pageIndex();
        applyCurrentPage();
        return true;
    }

    /** True if {@link #back()} would do something. */
    public boolean canGoBack() { return !folderStack.isEmpty(); }

    /** How many folders deep the current level is; 0 at the root. */
    public int folderDepth() { return folderStack.size(); }

    /**
     * Moves to the next sibling page within the current folder, wrapping around. Returns false
     * (and does nothing) if the current folder only has one page.
     */
    public boolean nextPage() {
        if (pages.size() <= 1) return false;
        commitCurrentPage();
        pageIndex = (pageIndex + 1) % pages.size();
        applyCurrentPage();
        return true;
    }

    /** Moves to the previous sibling page within the current folder, wrapping around. */
    public boolean previousPage() {
        if (pages.size() <= 1) return false;
        commitCurrentPage();
        pageIndex = (pageIndex - 1 + pages.size()) % pages.size();
        applyCurrentPage();
        return true;
    }

    /** Jumps directly to a sibling page within the current folder by index. */
    public boolean goToPage(int index) {
        if (index < 0 || index >= pages.size()) return false;
        commitCurrentPage();
        pageIndex = index;
        applyCurrentPage();
        return true;
    }

    /**
     * Appends a new, initially empty page to the current folder and switches to it. Useful when
     * building a folder's content up front, e.g. inside {@link DeckLayout#populate}, rather than
     * reacting to {@link #nextPage()} calls at runtime.
     */
    public void addPage() {
        commitCurrentPage();
        List<Map<Integer, DeckButton>> updated = new ArrayList<>(pages);
        updated.add(new HashMap<>());
        pages = List.copyOf(updated);
        pageIndex = pages.size() - 1;
        applyCurrentPage();
    }

    /**
     * A snapshot of every page in the current folder, capturing whatever is live on the
     * currently showing page first. Used to pull a folder's content back out after a
     * {@link DeckLayout#populate} call finishes.
     */
    public List<Map<Integer, DeckButton>> exportPages() {
        commitCurrentPage();
        return pages;
    }

    /** Number of sibling pages in the current folder. Always at least 1. */
    public int pageCount() { return pages.size(); }

    /** Index of the page currently showing within the current folder. */
    public int currentPageIndex() { return pageIndex; }

    /**
     * Replaces the base level's pages outright and clears the whole folder stack; there is
     * nothing to {@link #back()} to after this. Use to (re)define what the deck shows with
     * nothing open, e.g. from a layout's {@link DeckLayout#populate}.
     */
    public void setRootPages(List<Map<Integer, DeckButton>> rootPages) {
        folderStack.clear();
        pages = (rootPages == null || rootPages.isEmpty()) ? List.of(Map.of()) : List.copyOf(rootPages);
        pageIndex = 0;
        applyCurrentPage();
    }

    public void setRootPage(Map<Integer, DeckButton> rootPage) {
        setRootPages(List.of(rootPage));
    }

    private void applyCurrentPage() {
        Map<Integer, DeckButton> page = pages.get(pageIndex);
        for (int i = 0; i < buttons.length(); i++) buttons.set(i, page.get(i));
        redrawAll();
    }

    /**
     * Copies the live button assignments back into {@code pages.get(pageIndex)} before we
     * navigate away from it. Without this, direct {@link #setButton} calls made while a page is
     * showing would be invisible to the folder/page bookkeeping and get silently discarded the
     * next time we navigate, since {@code buttons} (not {@code pages}) is the array
     * {@code setButton} actually writes to.
     */
    private void commitCurrentPage() {
        Map<Integer, DeckButton> snapshot = new HashMap<>();
        for (int i = 0; i < buttons.length(); i++) {
            DeckButton b = buttons.get(i);
            if (b != null) snapshot.put(i, b);
        }
        List<Map<Integer, DeckButton>> updated = new ArrayList<>(pages);
        updated.set(pageIndex, Map.copyOf(snapshot));
        pages = List.copyOf(updated);
    }

    /**
     * @deprecated use {@link #openFolder(Map)}, which does the same thing under a clearer name
     *             now that folders can also have multiple pages.
     */
    @Deprecated
    public void pushPage(Map<Integer, DeckButton> page) { openFolder(page); }

    /** @deprecated use {@link #back()}. */
    @Deprecated
    public boolean popPage() { return back(); }

    /** @deprecated use {@link #folderDepth()}. */
    @Deprecated
    public int pageDepth() { return folderDepth(); }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Routes an event to the assigned button. Feed everything from
     * {@link StreamDeckManager#drainEvents} through here.
     *
     * @return true if this surface handled the event
     */
    public boolean handle(DeckEvent event) {
        if (!deckId.equals(event.deckId())) return false;

        switch (event) {
            case DeckEvent.KeyDown down -> {
                DeckButton button = button(down.key());
                if (button == null) return false;
                button.onDown(this, down.key());
                return true;
            }
            case DeckEvent.KeyUp up -> {
                DeckButton button = button(up.key());
                if (button == null) return false;
                button.onUp(this, up.key());
                return true;
            }
            case DeckEvent.Connected ignored -> {
                redrawAll();
                return true;
            }
            default -> {
            }
        }
        return false;
    }
}