package dev.wolfieboy09.streamdecked.client;

import com.mojang.logging.LogUtils;
import dev.wolfieboy09.sd5j.image.DeckImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Loads Minecraft textures into {@link DeckImage}s; cached until {@link #invalidate()}. */
@SuppressWarnings("unused")
public final class DeckTextures {
    public static final Logger LOGGER = LogUtils.getLogger();

    private DeckTextures() {}

    private static final Map<Identifier, DeckImage> CACHE = new ConcurrentHashMap<>();

    /** Side of the square checkerboard {@link #placeholder()} draws. Scaled to fit by callers. */
    private static final int PLACEHOLDER_SIZE = 64;
    private static final int PLACEHOLDER_LIGHT = 0xFF3A4048;
    private static final int PLACEHOLDER_DARK = 0xFF23272D;

    /**
     * A checkerboard standing in for a texture that could not be loaded, so a missing icon is
     * visible on the deck instead of silently blank. Scaled to fit whatever it is handed to.
     */
    public static DeckImage placeholder() {
        DeckImage image = new DeckImage(PLACEHOLDER_SIZE, PLACEHOLDER_SIZE);
        int cell = PLACEHOLDER_SIZE / 8;
        for (int y = 0; y < PLACEHOLDER_SIZE; y++) {
            for (int x = 0; x < PLACEHOLDER_SIZE; x++) {
                boolean light = ((x / cell) + (y / cell)) % 2 == 0;
                image.set(x, y, light ? PLACEHOLDER_LIGHT : PLACEHOLDER_DARK);
            }
        }
        return image;
    }

    /** {@link #load(Identifier)}, falling back to {@link #placeholder()}. */
    public static DeckImage loadOrPlaceholder(Identifier texture) {
        return load(texture).orElseGet(DeckTextures::placeholder);
    }

    /** {@link #item(Item)}, falling back to {@link #placeholder()}. */
    public static DeckImage itemOrPlaceholder(Item item) {
        return item(item).orElseGet(DeckTextures::placeholder);
    }

    /** {@link #item(ItemStack)}, falling back to {@link #placeholder()}. */
    public static DeckImage itemOrPlaceholder(ItemStack stack) {
        return item(stack).orElseGet(DeckTextures::placeholder);
    }

    /** {@link #block(Block)}, falling back to {@link #placeholder()}. */
    public static DeckImage blockOrPlaceholder(Block block) {
        return block(block).orElseGet(DeckTextures::placeholder);
    }

    /**
     * Loads a texture directly from the resource manager.
     *
     * <p>Empty when the texture is missing. A missing texture is usually a content problem
     * rather than a bug, so nothing here throws: an icon that a mod may not have, or one that
     * only exists in a resource pack, should not stop a deck from being built. Use the
     * {@code ...OrPlaceholder} variants when you would rather draw something than branch.
     */
    public static Optional<DeckImage> load(Identifier texture) {
        Identifier png = Identifier.fromNamespaceAndPath(
                texture.getNamespace(),
                texture.getPath().endsWith(".png")
                        ? texture.getPath()
                        : texture.getPath() + ".png"
        );

        DeckImage cached = CACHE.get(png);
        if (cached != null) {
            return Optional.of(cached.copy());
        }

        try (InputStream in = Minecraft.getInstance()
                .getResourceManager()
                .open(png)) {

            DeckImage image = DeckImage.decode(in);
            CACHE.put(png, image);
            return Optional.of(image.copy());
        } catch (IOException e) {
            LOGGER.warn("Stream Deck texture not found: {}", png);
            return Optional.empty();
        }
    }


    /** Loads an item's conventional texture. */
    public static Optional<DeckImage> item(Item item) {
        return item(new ItemStack(item));
    }

    /**
     * Loads an item's conventional texture. Block items have no item texture and fall back to
     * their block texture ({@code textures/item/...} -> {@code textures/block/...}).
     */
    public static Optional<DeckImage> item(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());

        Optional<DeckImage> image = load(Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/item/" + id.getPath() + ".png"
        ));

        return image.isPresent() ? image : load(Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/block/" + id.getPath() + ".png"
        ));
    }

    /** Loads a block's conventional texture. */
    public static Optional<DeckImage> block(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);

        return load(Identifier.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/block/" + id.getPath() + ".png"
        ));
    }

    /** Loads an item texture from its registry ID. */
    public static Optional<DeckImage> loadItemTexture(Identifier itemId) {
        return load(Identifier.fromNamespaceAndPath(
                itemId.getNamespace(),
                "textures/item/" + itemId.getPath() + ".png"
        ));
    }

    /** Loads a block texture from its registry ID. */
    public static Optional<DeckImage> loadBlockTexture(Identifier blockId) {
        return load(Identifier.fromNamespaceAndPath(
                blockId.getNamespace(),
                "textures/block/" + blockId.getPath() + ".png"
        ));
    }

    /** Scales with nearest-neighbor interpolation; bilinear would blur pixel art. */
    public static DeckImage pixelScale(DeckImage source, int width, int height) {
        DeckImage out = new DeckImage(width, height);

        for (int y = 0; y < height; y++) {
            int sy = y * source.height() / height;

            for (int x = 0; x < width; x++) {
                int sx = x * source.width() / width;
                out.set(x, y, source.get(sx, sy));
            }
        }

        return out;
    }

    /** Scales to fit inside the box with nearest-neighbor interpolation, centred on a background. */
    public static DeckImage pixelFit(
            DeckImage source,
            int width,
            int height,
            int background
    ) {
        int scale = Math.clamp(
                Math.min(
                        width / source.width(),
                        height / source.height()
                ),
                1,
                Integer.MAX_VALUE
        );

        DeckImage scaled = pixelScale(
                source,
                source.width() * scale,
                source.height() * scale
        );

        DeckImage out = DeckImage.filled(width, height, background);

        out.draw(
                scaled,
                (width - scaled.width()) / 2,
                (height - scaled.height()) / 2
        );

        return out;
    }

    public static void invalidate() {
        CACHE.clear();
    }
}
