package us.z1x.amplio.mixin;

import us.z1x.amplio.Amplio;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
	@Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJ)V"))
	private void amplio$beginWorld(float tickDelta, long startTime, CallbackInfo ci) {
		Amplio.beginWorld();
	}

	@Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJ)V", shift = At.Shift.AFTER))
	private void amplio$endWorld(float tickDelta, long startTime, CallbackInfo ci) {
		Amplio.endWorld();
	}
}
