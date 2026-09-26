package dev.wolfieboy09.streamdecked.mixin;

import dev.wolfieboy09.streamdecked.StreamDeckedMixinDetection;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(CrashReport.class)
public class CrashReportMixin {
    @Inject(method = "getFriendlyReport(Lnet/minecraft/ReportType;Ljava/util/List;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
    private void streamdecked$prependTaint(ReportType reportType, List<String> extraInfo, CallbackInfoReturnable<String> cir) {
        String section = StreamDeckedMixinDetection.section();
        if (!section.isEmpty()) {
            cir.setReturnValue(section + cir.getReturnValue());
        }
    }
}
