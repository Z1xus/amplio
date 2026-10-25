package us.z1x.amplio;

import us.z1x.amplio.mixin.MinecraftAccessor;
import us.z1x.amplio.upscale.Fsr1Upscaler;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Amplio implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Amplio");
	public static AmplioConfig config = new AmplioConfig();

	private static RenderTarget lowTarget;
	private static RenderTarget windowTarget;
	private static Fsr1Upscaler upscaler;

	@Override
	public void onInitializeClient() {
		config = AmplioConfig.load();
	}

	public static void beginWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		int width = Math.max(1, Math.round(minecraft.width * config.scale));
		int height = Math.max(1, Math.round(minecraft.height * config.scale));

		if (config.upscaler == UpscalerType.OFF || (width == minecraft.width && height == minecraft.height)) {
			return;
		}

		windowTarget = minecraft.getRenderTarget();

		if (lowTarget == null) {
			lowTarget = new RenderTarget(width, height, true);
		} else if (lowTarget.width != width || lowTarget.height != height) {
			lowTarget.resize(width, height);
		}

		lowTarget.clearChannels = windowTarget.clearChannels;
		((MinecraftAccessor) minecraft).amplio$setRenderTarget(lowTarget);
		lowTarget.bindWrite(true);
	}

	public static void endWorld() {
		if (windowTarget == null) {
			return;
		}

		RenderTarget target = windowTarget;
		windowTarget = null;
		((MinecraftAccessor) Minecraft.getInstance()).amplio$setRenderTarget(target);

		if (upscaler == null) {
			upscaler = new Fsr1Upscaler();
		}

		upscaler.upscale(lowTarget, target, config.sharpness);
		target.bindWrite(true);
	}

	public static int scaleViewportWidth(int width) {
		return windowTarget != null && width == Minecraft.getInstance().width ? lowTarget.width : width;
	}

	public static int scaleViewportHeight(int height) {
		return windowTarget != null && height == Minecraft.getInstance().height ? lowTarget.height : height;
	}
}
