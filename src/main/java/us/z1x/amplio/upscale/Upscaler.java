package us.z1x.amplio.upscale;

import net.minecraft.client.render.pipeline.RenderTarget;
import us.z1x.amplio.Quality;
import us.z1x.amplio.ScaledTarget;

public interface Upscaler {
    // picks the render size for this output size, call before reading renderWidth and renderHeight
    void prepare(int outputWidth, int outputHeight, Quality quality);

    int renderWidth();

    int renderHeight();

    // temporal upscalers need a sub-pixel jitter and motion vectors every frame
    boolean temporal();

    void upscale(ScaledTarget source, RenderTarget target);

    void close();
}
