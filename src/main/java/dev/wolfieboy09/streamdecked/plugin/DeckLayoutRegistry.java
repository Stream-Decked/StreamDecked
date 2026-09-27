package dev.wolfieboy09.streamdecked.plugin;

import dev.wolfieboy09.sd5j.image.DeckImage;
import dev.wolfieboy09.sd5j.layout.DeckLayout;
import dev.wolfieboy09.streamdecked.StreamDecked;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;

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

    /**
     * Registers a layout, with the icon shown on the Modspace key that enters it.
     *
     * <p>The icon is nullable on purpose: a texture that failed to load is a content problem,
     * not a programming error, and forcing every mod to null-check one shared helper is worse
     * than a missing folder with a line in the log. Pass
     * {@code DeckTextures.blockOrPlaceholder(block)} to always get a real image.
     */
    public void register(ResourceLocation id, @Nullable DeckImage icon, DeckLayout layout) {
        register(id, icon, PRIORITY_DEFAULT, layout);
    }

    /** @see #register(ResourceLocation, DeckImage, DeckLayout) */
    public void register(ResourceLocation id, @Nullable DeckImage icon, int priority, DeckLayout layout) {
        if (id == null || layout == null) {
            throw new IllegalArgumentException("id and layout are required");
        }
        if (icon == null) {
            StreamDecked.LOGGER.warn(
                    "Deck layout {} has no icon, skipping it. The texture is probably missing or only in a resource pack.",
                    id);
            return;
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
