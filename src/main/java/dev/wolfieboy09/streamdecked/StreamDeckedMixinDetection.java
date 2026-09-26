package dev.wolfieboy09.streamdecked;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.Config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class StreamDeckedMixinDetection {
    private static final String MOD_PACKAGE = "dev.wolfieboy09.streamdecked";
    private static final String OUR_MIXIN_PACKAGE = MOD_PACKAGE + ".mixin";

    private static final Method GET_MIXINS_FOR = lookupGetMixinsFor();

    private static volatile Map<String, Set<String>> taints;
    private static volatile boolean logged;

    private StreamDeckedMixinDetection() {}

    public static void preach() {
        if (logged) {
            return;
        }
        logged = true;
        Map<String, Set<String>> tainted = taintedClasses();
        if (tainted.isEmpty()) {
            StreamDecked.LOGGER.info(
                    "Stream Deck taint check: no foreign mixins found across {} registered mixin config(s).",
                    censusSize());
            return;
        }
        for (Map.Entry<String, Set<String>> entry : tainted.entrySet()) {
            StreamDecked.LOGGER.warn(
                    "Stream Deck class {} is tainted by mixins from another mod. Stream Deck behaviour may differ "
                            + "from a clean install, and a bug here may not be reproducible without them.{}",
                    entry.getKey(), describeTaint(entry.getValue()));
        }
    }

    public static String section() {
        Map<String, Set<String>> tainted = taintedClasses();
        if (tainted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("---------------- Stream Deck Has Been Tainted ----------------\n");
        sb.append("Stream Deck is tainted. Functionality may not be the same, and a bug may not\n");
        sb.append("reproduce once these mods are removed.\n\n");
        for (Map.Entry<String, Set<String>> entry : tainted.entrySet()) {
            sb.append(entry.getKey()).append(" is tainted by:").append('\n');
            for (String tainter : new TreeSet<>(entry.getValue())) {
                sb.append("    ").append(tainter).append('\n');
            }
            sb.append('\n');
        }
        sb.append("--------------------------------------------------------------\n\n");
        return sb.toString();
    }

    private static Map<String, Set<String>> taintedClasses() {
        Map<String, Set<String>> snapshot = taints;
        if (snapshot == null) {
            synchronized (StreamDeckedMixinDetection.class) {
                snapshot = taints;
                if (snapshot == null) {
                    snapshot = census();
                    taints = snapshot;
                }
            }
        }
        Map<String, Set<String>> tainted = new LinkedHashMap<>();
        snapshot.forEach((target, tainters) -> {
            if (!tainters.isEmpty()) {
                tainted.put(target, tainters);
            }
        });
        return tainted;
    }

    // getMixinConfigs and Config.create(String, MixinEnvironment) are the only public way to enumerate every
    // registered config, and are deprecated upstream with no public replacement. The two-arg create is kept
    // because it returns already-registered configs from Mixin's own cache instead of rebuilding them.
    @SuppressWarnings("deprecation")
    private static Map<String, Set<String>> census() {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        if (environment == null) {
            return found;
        }
        List<String> configNames;
        try {
            configNames = new ArrayList<>(environment.getMixinConfigs());
        } catch (Throwable t) {
            StreamDecked.LOGGER.warn("Stream Deck taint check could not enumerate mixin configs.", t);
            return found;
        }
        for (String configName : configNames) {
            if (configName == null) {
                continue;
            }
            collectConfig(configName, environment, found);
        }
        return found;
    }

    @SuppressWarnings("deprecation")
    private static void collectConfig(String configName, MixinEnvironment environment, Map<String, Set<String>> found) {
        IMixinConfig config;
        try {
            Config handle = Config.create(configName, environment);
            if (handle == null) {
                return;
            }
            config = handle.getConfig();
        } catch (Throwable t) {
            StreamDecked.LOGGER.debug("Stream Deck taint check skipped unreadable mixin config {}.", configName, t);
            return;
        }
        if (config == null || isOurs(config.getMixinPackage())) {
            return;
        }
        Set<String> targets;
        try {
            targets = new LinkedHashSet<>(config.getTargets());
        } catch (Throwable t) {
            return;
        }
        for (String target : targets) {
            if (target == null || !target.startsWith(MOD_PACKAGE)) {
                continue;
            }
            Set<String> tainters = found.computeIfAbsent(target, k -> new TreeSet<>());
            for (IMixinInfo info : mixinsFor(config, target)) {
                String mixinName = info.getName();
                if (mixinName != null && !mixinName.startsWith(OUR_MIXIN_PACKAGE)) {
                    tainters.add(mixinName);
                }
            }
        }
    }

    private static Method lookupGetMixinsFor() {
        try {
            Method method = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig")
                    .getMethod("getMixinsFor", String.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<IMixinInfo> mixinsFor(IMixinConfig config, String target) {
        if (GET_MIXINS_FOR == null) {
            return List.of();
        }
        try {
            Object result = GET_MIXINS_FOR.invoke(config, target);
            return result instanceof List<?> list ? (List<IMixinInfo>) list : List.of();
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static boolean isOurs(String value) {
        return value != null && value.startsWith(MOD_PACKAGE);
    }

    private static String describeTaint(Set<String> tainters) {
        StringBuilder sb = new StringBuilder();
        for (String tainter : new TreeSet<>(tainters)) {
            sb.append("\n    ").append(tainter);
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private static int censusSize() {
        try {
            MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
            return environment == null ? 0 : environment.getMixinConfigs().size();
        } catch (Throwable t) {
            return 0;
        }
    }
}
