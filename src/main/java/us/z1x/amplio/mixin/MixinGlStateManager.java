package us.z1x.amplio.mixin;

import us.z1x.amplio.Amplio;
import net.minecraft.client.render.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// the world pass sizes its viewport from the window, so full window viewports shrink to the render target
@Mixin(GlStateManager.class)
public class MixinGlStateManager {
	@ModifyVariable(method = "viewport", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private static int amplio$scaleWidth(int width) {
		return Amplio.scaleViewportWidth(width);
	}

	@ModifyVariable(method = "viewport", at = @At("HEAD"), argsOnly = true, ordinal = 3)
	private static int amplio$scaleHeight(int height) {
		return Amplio.scaleViewportHeight(height);
	}
}
