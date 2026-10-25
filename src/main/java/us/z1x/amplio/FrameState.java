package us.z1x.amplio;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;

public final class FrameState {
    public static final float[] projection = new float[16];
    public static final float[] jitteredProjection = new float[16];
    public static final float[] modelView = new float[16];
    public static final float[] previousProjection = new float[16];
    public static final float[] previousModelView = new float[16];
    public static final float[] cameraDelta = new float[3];
    public static float jitterX;
    public static float jitterY;
    public static boolean jitter;
    // set when the temporal history no longer matches the scene: a teleport, a new world or a skipped frame
    public static boolean reset = true;

    private static int width;
    private static int height;
    private static int index;
    private static double cameraX, cameraY, cameraZ;
    private static double previousX, previousY, previousZ;
    private static Entity lastCamera;
    private static long lastFrame;

    private FrameState() {
    }

    public static void begin(float tickDelta, int renderWidth, int renderHeight, int outputWidth, boolean jitter) {
        FrameState.jitter = jitter;
        width = renderWidth;
        height = renderHeight;

        // a halton(2, 3) sequence long enough to cover each output pixel, as the dlss guide recommends
        float ratio = (float) outputWidth / renderWidth;
        int phases = Math.max(8, (int) Math.ceil(8.0F * ratio * ratio));
        index = index % phases + 1;
        jitterX = jitter ? halton(index, 2) - 0.5F : 0.0F;
        jitterY = jitter ? halton(index, 3) - 0.5F : 0.0F;

        Entity camera = Minecraft.getInstance().getCamera();
        previousX = cameraX;
        previousY = cameraY;
        previousZ = cameraZ;
        cameraX = camera.prevX + (camera.x - camera.prevX) * tickDelta;
        cameraY = camera.prevY + (camera.y - camera.prevY) * tickDelta;
        cameraZ = camera.prevZ + (camera.z - camera.prevZ) * tickDelta;
        cameraDelta[0] = (float) (cameraX - previousX);
        cameraDelta[1] = (float) (cameraY - previousY);
        cameraDelta[2] = (float) (cameraZ - previousZ);

        long now = System.nanoTime();
        reset = camera != lastCamera || now - lastFrame > 250_000_000L
            || cameraDelta[0] * cameraDelta[0] + cameraDelta[1] * cameraDelta[1] + cameraDelta[2] * cameraDelta[2] > 64.0F;
        lastCamera = camera;
        lastFrame = now;
    }

    public static void end() {
        jitter = false;
        System.arraycopy(projection, 0, previousProjection, 0, 16);
        System.arraycopy(modelView, 0, previousModelView, 0, 16);
    }

    public static void captureProjection() {
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
    }

    public static void captureModelView() {
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelView);
    }

    // shifts the projection by the jitter: clip.xy += jitter * clip.w, with the matrix in column major order
    public static void applyJitter() {
        if (!jitter) {
            return;
        }

        float[] m = jitteredProjection;
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, m);
        float x = 2.0F * jitterX / width;
        float y = 2.0F * jitterY / height;

        for (int column = 0; column < 4; column++) {
            m[column * 4] += x * m[column * 4 + 3];
            m[column * 4 + 1] += y * m[column * 4 + 3];
        }

        GL11.glLoadMatrixf(m);
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;

        for (int i = index; i > 0; i /= base) {
            fraction /= base;
            result += fraction * (i % base);
        }

        return result;
    }
}
