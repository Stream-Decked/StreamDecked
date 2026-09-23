package dev.wolfieboy09.streamdecked.plugin;

import dev.wolfieboy09.sd5j.core.DeckLayout;
import dev.wolfieboy09.sd5j.core.image.DeckImage;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects layout registrations from every discovered {@link StreamDeckedPlugin}.
 * Registrations sharing an id namespace are grouped into one auto-generated folder with the
 * given icon. Ids must be unique within a mod; registering the same id twice throws. Priority
 * decides order of entries within a folder and of folders on the root.
 */
@SuppressWarnings("unused")
public final class DeckLayoutRegistry {
    /** A registered layout with the identity, icon, and ordering it was registered under. */
    public record Entry(ResourceLocation id, int priority, DeckImage icon, DeckLayout layout) {}

    public static final int PRIORITY_LOWEST = -1000;
    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_HIGHEST = 1000;

    private final Map<ResourceLocation, Entry> entries = new LinkedHashMap<>();

    public void register(ResourceLocation id, DeckImage icon, DeckLayout layout) {
        register(id, icon, PRIORITY_DEFAULT, layout);
    }

    public void register(ResourceLocation id, DeckImage icon, int priority, DeckLayout layout) {
        if (id == null || icon == null || layout == null) {
            throw new IllegalArgumentException("id, icon, and layout are required");
        }
        Entry existing = entries.putIfAbsent(id, new Entry(id, priority, icon, layout));
        if (existing != null) {
            throw new IllegalStateException("duplicate deck layout id: " + id);
        }
    }

    public boolean isRegistered(ResourceLocation id) {
        return entries.containsKey(id);
    }

    /** Registered layouts, sorted by priority then registration order. */
    public List<Entry> getEntries() {
        List<Entry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingInt(Entry::priority));
        return List.copyOf(sorted);
    }
}
