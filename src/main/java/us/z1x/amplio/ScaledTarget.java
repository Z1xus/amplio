package us.z1x.amplio;

import net.minecraft.client.render.pipeline.RenderTarget;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;

// the low resolution world target, with a depth texture instead of a renderbuffer so upscalers can sample depth
public final class ScaledTarget extends RenderTarget {
    // no initializer: the super constructor already ran createBuffers when field initializers would run
    private int depthTexture;
    private int sceneDepthTexture;
    private boolean sceneDepthSaved;

    public ScaledTarget(int width, int height) {
        super(width, height, true);
    }

    public int getDepthTexture() {
        return depthTexture;
    }

    public int getSceneDepthTexture() {
        return sceneDepthSaved ? sceneDepthTexture : depthTexture;
    }

    public boolean isSceneDepthSaved() {
        return sceneDepthSaved;
    }

    public void forgetSceneDepth() {
        sceneDepthSaved = false;
    }

    public void saveSceneDepth() {
        GL43C.glCopyImageSubData(depthTexture, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, sceneDepthTexture, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        sceneDepthSaved = true;
    }

    @Override
    public void createBuffers(int width, int height) {
        super.createBuffers(width, height);
        GL30C.glDeleteRenderbuffers(depthBufferId);
        depthBufferId = -1;

        depthTexture = createDepthTexture(width, height);
        sceneDepthTexture = createDepthTexture(width, height);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, frameBufferId);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depthTexture, 0);
        clear();
        unbindWrite();
    }

    @Override
    public void destroyBuffers() {
        if (depthTexture != 0) {
            GL11C.glDeleteTextures(depthTexture);
            depthTexture = 0;
            GL11C.glDeleteTextures(sceneDepthTexture);
            sceneDepthTexture = 0;
        }

        super.destroyBuffers();
    }

    private static int createDepthTexture(int width, int height) {
        int texture = GL11C.glGenTextures();
        GlStateManager.bindTexture(texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, width, height, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_INT, (ByteBuffer) null);
        GlStateManager.bindTexture(0);
        return texture;
    }
}
