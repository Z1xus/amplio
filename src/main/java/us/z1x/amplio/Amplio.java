package us.z1x.amplio;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import us.z1x.amplio.mixin.MinecraftAccessor;
import us.z1x.amplio.upscale.DlssUpscaler;
import us.z1x.amplio.upscale.Fsr1Upscaler;
import us.z1x.amplio.upscale.Upscaler;

public final class Amplio implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("Amplio");
	public static AmplioConfig config = new AmplioConfig();

	private static ScaledTarget lowTarget;
	private static RenderTarget windowTarget;
	private static Upscaler upscaler;
	private static UpscalerType active = UpscalerType.OFF;
	private static boolean dlssFailed;
	private static boolean disabled;

	@Override
	public void onInitializeClient() {
		config = AmplioConfig.load();
	}

	public static void beginWorld(float tickDelta) {
		Upscaler upscaler = upscaler();
		if (upscaler == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();

		try {
			upscaler.prepare(minecraft.width, minecraft.height, config.quality);
		} catch (RuntimeException e) {
			fail(e);
			return;
		}

		int width = upscaler.renderWidth();
		int height = upscaler.renderHeight();

		// at native size only temporal upscalers still do work, as anti-aliasing
		if (width == minecraft.width && height == minecraft.height && !upscaler.temporal()) {
			return;
		}

		windowTarget = minecraft.getRenderTarget();

		if (lowTarget == null) {
			lowTarget = new ScaledTarget(width, height);
		} else if (lowTarget.width != width || lowTarget.height != height) {
			lowTarget.resize(width, height);
		}

		lowTarget.clearChannels = windowTarget.clearChannels;
		FrameState.begin(tickDelta, width, height, minecraft.width, upscaler.temporal());
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

		try {
			upscaler.upscale(lowTarget, target);
		} catch (RuntimeException e) {
			fail(e);
		}

		FrameState.end();
		target.bindWrite(true);
	}

	public static int scaleViewportWidth(int width) {
		return windowTarget != null && width == Minecraft.getInstance().width ? lowTarget.width : width;
	}

	public static int scaleViewportHeight(int height) {
		return windowTarget != null && height == Minecraft.getInstance().height ? lowTarget.height : height;
	}

	// dlss falls back to fsr 1 for the session, a failing fsr 1 turns upscaling off
	private static void fail(RuntimeException e) {
		LOGGER.error("{} failed, falling back", active, e);

		if (active == UpscalerType.DLSS) {
			dlssFailed = true;
		} else {
			disabled = true;
		}

		try {
			upscaler.close();
		} catch (RuntimeException ignored) {
			// the upscaler is already broken, its resources go away with the context
		}

		upscaler = null;
		active = UpscalerType.OFF;
	}

	// swaps the upscaler when the setting changes
	private static Upscaler upscaler() {
		UpscalerType wanted = disabled ? UpscalerType.OFF : config.upscaler == UpscalerType.DLSS && dlssFailed ? UpscalerType.FSR1 : config.upscaler;

		if (wanted != active) {
			if (upscaler != null) {
				upscaler.close();
				upscaler = null;
			}

			active = wanted;
			upscaler = create(wanted);
		}

		return upscaler;
	}

	private static Upscaler create(UpscalerType type) {
		return switch (type) {
			case OFF -> null;
			case FSR1 -> new Fsr1Upscaler();
			case DLSS -> {
				try {
					yield new DlssUpscaler();
				} catch (Throwable e) {
					LOGGER.warn("DLSS is unavailable, using FSR 1 instead: {}", e.getMessage());
					dlssFailed = true;
					active = UpscalerType.FSR1;
					yield new Fsr1Upscaler();
				}
			}
		};
	}
}
