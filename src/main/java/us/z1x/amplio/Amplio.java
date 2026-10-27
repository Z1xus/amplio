package us.z1x.amplio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import us.z1x.amplio.mixin.MinecraftAccessor;
import us.z1x.amplio.upscale.DlssUpscaler;
import us.z1x.amplio.upscale.Fsr1Upscaler;
import us.z1x.amplio.upscale.Upscaler;

public final class Amplio {
    public static final Logger LOGGER = LogManager.getLogger("Amplio");
    public static final AmplioConfig CONFIG = AmplioConfig.load();

    private static ScaledTarget lowTarget;
    private static RenderTarget windowTarget;
    private static Upscaler upscaler;
    private static UpscalerType active = UpscalerType.OFF;
    private static boolean dlssFailed;
    private static boolean disabled;

    private Amplio() {
    }

    public static void beginWorld(float tickDelta) {
        Upscaler upscaler = upscaler();
        if (upscaler == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        try {
            upscaler.prepare(minecraft.width, minecraft.height, CONFIG.quality);
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

        // the target sets viewports while it is created, so it must exist before the viewport hook is armed
        if (lowTarget == null) {
            lowTarget = new ScaledTarget(width, height);
        } else if (lowTarget.width != width || lowTarget.height != height) {
            lowTarget.resize(width, height);
        }

        windowTarget = minecraft.getRenderTarget();

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

    public static boolean coversWindow(int x, int y, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        return windowTarget != null && x == 0 && y == 0 && width == minecraft.width && height == minecraft.height;
    }

    public static int renderWidth() {
        return lowTarget.width;
    }

    public static int renderHeight() {
        return lowTarget.height;
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

    private static Upscaler upscaler() {
        UpscalerType wanted = CONFIG.upscaler;

        if (disabled) {
            wanted = UpscalerType.OFF;
        } else if (wanted == UpscalerType.DLSS && dlssFailed) {
            wanted = UpscalerType.FSR1;
        }

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
