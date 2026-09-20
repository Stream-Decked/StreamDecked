package dev.wolfieboy09.streamdecked.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//TODO - Remove when actual mixins exist
// This class only exists so mixin detection can function correctly
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
}