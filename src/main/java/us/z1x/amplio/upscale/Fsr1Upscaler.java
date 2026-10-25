package us.z1x.amplio.upscale;

import net.minecraft.client.render.pipeline.RenderTarget;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;

// amd fidelityfx super resolution 1: easu upscales into an intermediate texture, rcas sharpens it into the target
public final class Fsr1Upscaler {
	private final int easu = Shaders.link("fullscreen.vsh", "easu.fsh");
	private final int rcas = Shaders.link("fullscreen.vsh", "rcas.fsh");
	private final int easuInputSize = GL20C.glGetUniformLocation(easu, "inputSize");
	private final int easuOutputSize = GL20C.glGetUniformLocation(easu, "outputSize");
	private final int rcasSharpness = GL20C.glGetUniformLocation(rcas, "sharpness");
	private final int framebuffer = GL30C.glGenFramebuffers();
	private int texture;
	private int width;
	private int height;

	public Fsr1Upscaler() {
		GL41C.glProgramUniform1i(easu, GL20C.glGetUniformLocation(easu, "source"), 0);
		GL41C.glProgramUniform1i(rcas, GL20C.glGetUniformLocation(rcas, "source"), 0);
	}

	public void upscale(RenderTarget source, RenderTarget target, float sharpness) {
		if (texture == 0 || width != target.width || height != target.height) {
			resize(target.width, target.height);
		}

		GlStateManager.disableBlend();
		GlStateManager.disableDepthTest();
		GlStateManager.depthMask(false);
		GlStateManager.colorMask(true, true, true, true);
		GlStateManager.activeTexture(GL13C.GL_TEXTURE0);
		GL11C.glViewport(0, 0, width, height);

		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
		GlStateManager.bindTexture(source.colorTextureId);
		GL20C.glUseProgram(easu);
		GL20C.glUniform2f(easuInputSize, source.width, source.height);
		GL20C.glUniform2f(easuOutputSize, width, height);
		GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, target.frameBufferId);
		GlStateManager.bindTexture(texture);
		GL20C.glUseProgram(rcas);
		GL20C.glUniform1f(rcasSharpness, sharpness);
		GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

		GL20C.glUseProgram(0);
		GlStateManager.bindTexture(0);
		GlStateManager.depthMask(true);
		GlStateManager.enableDepthTest();
	}

	private void resize(int width, int height) {
		this.width = width;
		this.height = height;

		if (texture != 0) {
			GL11C.glDeleteTextures(texture);
		}

		texture = GL11C.glGenTextures();
		GlStateManager.bindTexture(texture);
		GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, 1, GL11C.GL_RGBA8, width, height);
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
		GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, texture, 0);
	}
}
