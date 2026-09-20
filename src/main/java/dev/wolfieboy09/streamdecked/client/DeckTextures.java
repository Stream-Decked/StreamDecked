package dev.wolfieboy09.streamdecked.client;

import com.mojang.logging.LogUtils;
import dev.wolfieboy09.streamdecked.core.image.DeckImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads Minecraft textures into {@link DeckImage}s. Flat files only for now.
 * Cached until {@link #invalidate()}.
 */
@SuppressWarnings("unused")
public final class DeckTextures {
    public static final Logger LOGGER = LogUtils.getLogger();

    private DeckTextures() {}

    private static final Map<ResourceLocation, DeckImage> CACHE = new ConcurrentHashMap<>();

    /**
     * Loads a texture directly from the resource manager.
     *
     * @param texture texture resource location
     * @return a copy of the decoded texture, or {@code null} if it is missing
     */
    @Nullable
    public static DeckImage load(ResourceLocation texture) {
        ResourceLocation png = ResourceLocation.fromNamespaceAndPath(
                texture.getNamespace(),
                texture.getPath().endsWith(".png")
                        ? texture.getPath()
                        : texture.getPath() + ".png"
        );

        DeckImage cached = CACHE.get(png);
        if (cached != null) {
            return cached.copy();
        }

        try (InputStream in = Minecraft.getInstance()
                .getResourceManager()
                .open(png)) {

            DeckImage image = DeckImage.decode(in);
            CACHE.put(png, image);
            return image.copy();
        } catch (IOException e) {
            LOGGER.warn("Stream Deck texture not found: {}", png);
            return null;
        }
    }


    /**
     * Loads an item's conventional texture.
     *
     * @param item Minecraft item
     * @return the item's texture, or {@code null} if it cannot be found
     */
    @Nullable
    public static DeckImage item(Item item) {
        return item(new ItemStack(item));
    }

    /**
     * Loads an item's conventional texture. Block items have no item texture and fall
     * back to their block texture ({@code textures/item/...} -> {@code textures/block/...}).
     *
     * @param stack item stack
     * @return the item's texture, or {@code null} if it cannot be found
     */
    @Nullable
    public static DeckImage item(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());

        DeckImage image = load(ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/item/" + id.getPath() + ".png"
        ));

        if (image == null) {
            image = load(ResourceLocation.fromNamespaceAndPath(
                    id.getNamespace(),
                    "textures/block/" + id.getPath() + ".png"
            ));
        }

        return image;
    }

    /**
     * Loads a block's conventional texture.
     *
     * @param block Minecraft block
     * @return the block's texture, or {@code null} if it cannot be found
     */
    @Nullable
    public static DeckImage block(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);

        return load(ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(),
                "textures/block/" + id.getPath() + ".png"
        ));
    }

    /** Loads an item texture from its registry ID. */
    public static DeckImage loadItemTexture(ResourceLocation itemId) {
        return load(ResourceLocation.fromNamespaceAndPath(
                itemId.getNamespace(),
                "textures/item/" + itemId.getPath() + ".png"
        ));
    }

    /**
     * Loads a block texture from its registry ID.
     */
    public static DeckImage loadBlockTexture(ResourceLocation blockId) {
        return load(ResourceLocation.fromNamespaceAndPath(
                blockId.getNamespace(),
                "textures/block/" + blockId.getPath() + ".png"
        ));
    }

    /**
     * Scales an image using nearest-neighbor interpolation.
     *
     * <p>This is appropriate for Minecraft's pixel-art textures because
     * bilinear filtering would blur the texture.</p>
     */
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

    /**
     * Scales an image using nearest-neighbor interpolation while preserving
     * its aspect ratio and centring it on a background.
     */
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

    /**
     * Clears the texture cache.
     */
    public static void invalidate() {
        CACHE.clear();
    }
}
