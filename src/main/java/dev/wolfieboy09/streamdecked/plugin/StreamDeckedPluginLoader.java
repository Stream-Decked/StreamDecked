package dev.wolfieboy09.streamdecked.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.wolfieboy09.streamdecked.StreamDecked;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.locating.IModFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Discovers every mod's {@code streamdecked.plugin.json} (if it has one) and loads the
 * {@link StreamDeckedPlugin}s it declares.
 * {@code class} is required; {@code required_mods} is optional (the entry is skipped unless
 * those mods are loaded). A {@code class} that cannot be resolved is skipped with a log line,
 * not a hard error, so one file can declare plugins with different optional dependencies.
 */
public final class StreamDeckedPluginLoader {
    private StreamDeckedPluginLoader() {}

    private static final String FILE_NAME = "streamdecked.plugin.json";

    private record PluginEntry(Optional<Class<? extends StreamDeckedPlugin>> pluginClass, List<String> requiredMods) {}

    private record PluginFileData(List<PluginEntry> plugins) {}

    /**
     * A class name that cannot be resolved is treated as "optional and absent," not a parse
     * error, since it may only exist when some other optional dependency is present.
     */
    private static final Codec<Optional<Class<? extends StreamDeckedPlugin>>> PLUGIN_CLASS_CODEC =
            Codec.STRING.comapFlatMap(name -> {
                try {
                    Class<?> found = Class.forName(name, false, StreamDeckedPluginLoader.class.getClassLoader());
                    try {
                        return DataResult.success(Optional.of(found.asSubclass(StreamDeckedPlugin.class)));
                    } catch (ClassCastException e) {
                        return DataResult.error(() -> "class " + name + " does not implement StreamDeckedPlugin");
                    }
                } catch (ClassNotFoundException e) {
                    return DataResult.success(Optional.empty());
                }
            }, clazz -> clazz.map(Class::getName).orElse(""));

    private static final Codec<PluginEntry> PLUGIN_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PLUGIN_CLASS_CODEC.fieldOf("class").forGetter(PluginEntry::pluginClass),
            Codec.STRING.listOf().optionalFieldOf("required_mods", List.of()).forGetter(PluginEntry::requiredMods)
    ).apply(instance, PluginEntry::new));

    private static final Codec<PluginFileData> PLUGIN_FILE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PLUGIN_ENTRY_CODEC.listOf().fieldOf("plugins").forGetter(PluginFileData::plugins)
    ).apply(instance, PluginFileData::new));

    public static List<StreamDeckedPlugin> discover() {
        List<StreamDeckedPlugin> plugins = new ArrayList<>();

        for (var modInfo : ModList.get().getMods()) {
            String modId = modInfo.getModId();
            IModFile file = modInfo.getOwningFile().getFile();

            Path resourcePath = file.findResource(FILE_NAME);
            if (!Files.exists(resourcePath)) continue;

            byte[] data;
            try {
                data = Files.readAllBytes(resourcePath);
            } catch (Exception e) {
                StreamDecked.LOGGER.debug("Could not read {} for {}", FILE_NAME, modId, e);
                continue;
            }

            loadFrom(new String(data, StandardCharsets.UTF_8), modId, plugins);
        }

        return plugins;
    }

    private static void loadFrom(String json, String source, List<StreamDeckedPlugin> out) {
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            StreamDecked.LOGGER.error("Failed to parse {} from {}: {}", FILE_NAME, source, e.getMessage());
            return;
        }

        DataResult<PluginFileData> result = PLUGIN_FILE_CODEC.parse(JsonOps.INSTANCE, element);
        Optional<PluginFileData> parsed = result.result();
        if (parsed.isEmpty()) {
            String message = result.error().map(DataResult.Error::message).orElse("unknown error");
            StreamDecked.LOGGER.error("Failed to parse {} from {}: {}", FILE_NAME, source, message);
            return;
        }

        StreamDecked.LOGGER.info("Found Stream Deck plugin declaration in {}", source);
        for (PluginEntry entry : parsed.get().plugins()) {
            if (entry.pluginClass().isEmpty()) {
                StreamDecked.LOGGER.warn("Plugin class in {} not found, skipping", source);
                continue;
            }
            Class<? extends StreamDeckedPlugin> pluginClass = entry.pluginClass().get();

            List<String> missing = entry.requiredMods().stream()
                    .filter(id -> !ModList.get().isLoaded(id))
                    .toList();
            if (!missing.isEmpty()) {
                StreamDecked.LOGGER.info("Plugin {} skipped: required mod(s) {} not loaded",
                        pluginClass.getName(), missing);
                continue;
            }

            try {
                out.add(pluginClass.getDeclaredConstructor().newInstance());
            } catch (Throwable t) {
                StreamDecked.LOGGER.error("Failed to load Stream Deck plugin {} from {}", pluginClass.getName(), source, t);
            }
        }
    }
}
