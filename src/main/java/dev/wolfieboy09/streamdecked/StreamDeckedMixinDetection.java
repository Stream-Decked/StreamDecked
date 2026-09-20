package dev.wolfieboy09.streamdecked;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class StreamDeckedMixinDetection {
    private static final String MOD_PACKAGE = "dev.wolfieboy09.streamdecked";
    private static final String OUR_MIXINS = MOD_PACKAGE + ".mixin";

    private static final Map<String, Set<String>> TAINTS = new HashMap<>();
    private static boolean immediateLog;

    private StreamDeckedMixinDetection() {}

    public static void exposeMixiners(String targetClassName, ClassNode targetClass, String mixinClassName) {
        if (!targetClassName.startsWith(MOD_PACKAGE)) {
            return;
        }
        Set<String> tainters = TAINTS.computeIfAbsent(targetClassName, k -> new HashSet<>());
        for (var method : targetClass.methods) {
            AnnotationNode merged = Annotations.getVisible(method, MixinMerged.class);
            String tainter = Annotations.getValue(merged, "mixin", (String) null);
            if (tainter != null && !tainter.startsWith(OUR_MIXINS)) {
                tainters.add(tainter);
            }
        }
        if (!tainters.isEmpty()) {
            String taint = describeTaint(tainters);
            targetClass.sourceFile = taint;
            targetClass.sourceDebug = taint;
            if (immediateLog) {
                logTaint(targetClassName, tainters);
            }
        }
    }

    public static void preach() {
        if (immediateLog) {
            return;
        }
        immediateLog = true;
        for (Map.Entry<String, Set<String>> entry : TAINTS.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                logTaint(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void logTaint(String targetClassName, Set<String> tainters) {
        StreamDecked.LOGGER.warn("Stream Deck class {} is tainted by foreign mixins: {}",
                targetClassName, describeTaint(tainters));
    }

    private static String describeTaint(Set<String> tainters) {
        StringBuilder sb = new StringBuilder("Mixins:");
        for (String tainter : tainters) {
            sb.append("\n\t\t * ").append(tainter);
        }
        sb.append("\n\t\t");
        return sb.toString();
    }
}