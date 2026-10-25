package us.z1x.amplio.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.render.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import us.z1x.amplio.Amplio;

// the world pass sizes its viewport from the window, so viewports covering the whole window shrink to the render target
@Mixin(GlStateManager.class)
public class MixinScaleViewport {
    @WrapMethod(method = "viewport")
    private static void amplio$scaleViewport(int x, int y, int width, int height, Operation<Void> original) {
        if (Amplio.coversWindow(x, y, width, height)) {
            original.call(0, 0, Amplio.renderWidth(), Amplio.renderHeight());
        } else {
            original.call(x, y, width, height);
        }
    }
}
