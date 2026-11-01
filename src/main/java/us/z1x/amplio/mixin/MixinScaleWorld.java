package us.z1x.amplio.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.z1x.amplio.Amplio;

@Mixin(GameRenderer.class)
public class MixinScaleWorld {
    @Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJ)V"))
    private void amplio$beginWorld(float tickDelta, long startTime, CallbackInfo ci) {
        Amplio.beginWorld(tickDelta);
    }

    // vanilla clears depth for the hand here, lucerna skips this with a shader pack and keeps the hand in the scene depth
    @Inject(method = "render(IFJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/platform/GlStateManager;clear(I)V", ordinal = 1))
    private void amplio$saveSceneDepth(int anaglyphRenderPass, float tickDelta, long renderTimeLimit, CallbackInfo ci) {
        Amplio.saveSceneDepth();
    }

    @Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJ)V", shift = At.Shift.AFTER))
    private void amplio$endWorld(float tickDelta, long startTime, CallbackInfo ci) {
        Amplio.endWorld();
    }
}
