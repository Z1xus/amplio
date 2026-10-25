package us.z1x.amplio.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.z1x.amplio.FrameState;

@Mixin(GameRenderer.class)
public class MixinCameraJitter {
    @Inject(method = "setupCamera", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", shift = At.Shift.AFTER))
    private void amplio$captureProjection(float tickDelta, int anaglyphRenderPass, CallbackInfo ci) {
        FrameState.captureProjection();
        FrameState.applyJitter();
    }

    @Inject(method = "setupCamera", at = @At("TAIL"))
    private void amplio$captureModelView(float tickDelta, int anaglyphRenderPass, CallbackInfo ci) {
        FrameState.captureModelView();
    }

    // sky, clouds and the hand set their own projections, they get the same jitter
    @Inject(method = {"renderItemInHand", "render(IFJ)V", "renderClouds"}, at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", shift = At.Shift.AFTER))
    private void amplio$jitterProjection(CallbackInfo ci) {
        FrameState.applyJitter();
    }
}
