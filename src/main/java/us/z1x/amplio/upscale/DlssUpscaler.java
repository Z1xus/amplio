package us.z1x.amplio.upscale;

import net.minecraft.client.render.pipeline.RenderTarget;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectFD;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreFD;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import us.z1x.amplio.Amplio;
import us.z1x.amplio.DlssModel;
import us.z1x.amplio.FrameState;
import us.z1x.amplio.Quality;
import us.z1x.amplio.ScaledTarget;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

// nvidia dlss, evaluated by the native vulkan bridge on images shared with this opengl context
public final class DlssUpscaler implements Upscaler {
    private static final int COLOR = 0, DEPTH = 1, MOTION = 2, OUTPUT = 3, IMAGES = 4;
    private static final int[] FORMATS = {GL11C.GL_RGBA8, GL30C.GL_R32F, GL30C.GL_RG16F, GL11C.GL_RGBA8};
    private static final boolean WINDOWS = Platform.get() == Platform.WINDOWS;
    private static final int[] NO_BUFFERS = {};

    private final SharedLibrary library;
    private final long create, error, query, resize, closeHandles, evaluate, destroy;
    private final long context;
    private final int program = Shaders.link("motion.vsh", "motion.fsh");
    private final int prepareFramebuffer = GL30C.glGenFramebuffers();
    private final int outputFramebuffer = GL30C.glGenFramebuffers();
    private final int[] textures = new int[IMAGES];
    private final int[] memoryObjects = new int[IMAGES];
    private final int[] layouts = {EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT, EXTSemaphore.GL_LAYOUT_GENERAL_EXT};
    private final int renderSize, projection, jitteredProjection, modelView, previousProjection, previousModelView, cameraDelta;
    private int glReady;
    private int vkReady;
    private int outputWidth, outputHeight, renderWidth, renderHeight;
    private Quality quality;
    private DlssModel model;
    private boolean reset = true;
    private long lastFrame;

    public DlssUpscaler() throws Exception {
        GLCapabilities capabilities = GL.getCapabilities();
        boolean interop = WINDOWS ? capabilities.GL_EXT_memory_object_win32 && capabilities.GL_EXT_semaphore_win32
            : capabilities.GL_EXT_memory_object_fd && capabilities.GL_EXT_semaphore_fd;
        if (!interop) {
            throw new UnsupportedOperationException("The OpenGL driver cannot share memory with Vulkan");
        }

        Path natives = Natives.extract();
        library = Library.loadNative(DlssUpscaler.class, "us.z1x.amplio", natives.resolve(Natives.library()).toString());
        create = library.getFunctionAddress("amplio_create");
        error = library.getFunctionAddress("amplio_error");
        query = library.getFunctionAddress("amplio_query");
        resize = library.getFunctionAddress("amplio_resize");
        closeHandles = library.getFunctionAddress("amplio_close_handles");
        evaluate = library.getFunctionAddress("amplio_evaluate");
        destroy = library.getFunctionAddress("amplio_destroy");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // struct CreateInfo { uint8 deviceUuid[16]; char *dataPath; }
            ByteBuffer info = stack.calloc(24);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, info.slice(0, 16));
            info.putLong(16, MemoryUtil.memAddress(stack.UTF8(natives.toAbsolutePath().toString())));
            context = JNI.invokePP(MemoryUtil.memAddress(info), create);
        }

        String message = lastError();
        if (!message.isEmpty()) {
            JNI.invokePV(context, destroy);
            library.free();
            throw new IllegalStateException(message);
        }

        renderSize = GL20C.glGetUniformLocation(program, "renderSize");
        projection = GL20C.glGetUniformLocation(program, "projection");
        jitteredProjection = GL20C.glGetUniformLocation(program, "jitteredProjection");
        modelView = GL20C.glGetUniformLocation(program, "modelView");
        previousProjection = GL20C.glGetUniformLocation(program, "previousProjection");
        previousModelView = GL20C.glGetUniformLocation(program, "previousModelView");
        cameraDelta = GL20C.glGetUniformLocation(program, "cameraDelta");
        GL41C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, "color"), 0);
        GL41C.glProgramUniform1i(program, GL20C.glGetUniformLocation(program, "depth"), 1);
    }

    @Override
    public void prepare(int outputWidth, int outputHeight, Quality quality) {
        DlssModel model = Amplio.CONFIG.dlssModel;

        if (outputWidth == this.outputWidth && outputHeight == this.outputHeight && quality == this.quality && model == this.model) {
            return;
        }

        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.quality = quality;
        this.model = model;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // struct QueryInfo { int64 outputWidth, outputHeight, quality, renderWidth, renderHeight; }
            ByteBuffer info = stack.calloc(40);
            info.putLong(0, outputWidth).putLong(8, outputHeight).putLong(16, quality.ngx);
            call(query, info);
            renderWidth = (int) info.getLong(24);
            renderHeight = (int) info.getLong(32);
        }

        recreate();
    }

    @Override
    public int renderWidth() {
        return renderWidth;
    }

    @Override
    public int renderHeight() {
        return renderHeight;
    }

    @Override
    public boolean temporal() {
        return true;
    }

    @Override
    public void upscale(ScaledTarget source, RenderTarget target) {
        GlStateManager.disableBlend();
        GlStateManager.disableDepthTest();
        GlStateManager.depthMask(false);
        GlStateManager.colorMask(true, true, true, true);

        // copy color and depth into the shared images and write motion vectors next to them
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prepareFramebuffer);
        GL11C.glViewport(0, 0, renderWidth, renderHeight);
        GL20C.glUseProgram(program);
        GL20C.glUniform2f(renderSize, renderWidth, renderHeight);
        GL20C.glUniformMatrix4fv(projection, false, FrameState.projection);
        GL20C.glUniformMatrix4fv(jitteredProjection, false, FrameState.jitteredProjection);
        GL20C.glUniformMatrix4fv(modelView, false, FrameState.modelView);
        GL20C.glUniformMatrix4fv(previousProjection, false, FrameState.previousProjection);
        GL20C.glUniformMatrix4fv(previousModelView, false, FrameState.previousModelView);
        GL20C.glUniform3fv(cameraDelta, FrameState.cameraDelta);
        GlStateManager.activeTexture(GL13C.GL_TEXTURE1);
        GlStateManager.bindTexture(source.getDepthTexture());
        GlStateManager.activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager.bindTexture(source.colorTextureId);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL20C.glUseProgram(0);

        EXTSemaphore.glSignalSemaphoreEXT(glReady, NO_BUFFERS, textures, layouts);
        GL11C.glFlush();

        long now = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // struct EvaluateInfo { float jitterX, jitterY, frameTime, reset; }
            ByteBuffer info = stack.calloc(16);
            info.putFloat(0, FrameState.jitterX).putFloat(4, FrameState.jitterY)
                .putFloat(8, lastFrame == 0 ? 16.0F : (now - lastFrame) / 1.0e6F).putFloat(12, reset || FrameState.reset ? 1.0F : 0.0F);
            JNI.invokePPI(context, MemoryUtil.memAddress(info), evaluate);
        }

        lastFrame = now;
        reset = false;
        EXTSemaphore.glWaitSemaphoreEXT(vkReady, NO_BUFFERS, textures, layouts);

        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, outputFramebuffer);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GL30C.glBlitFramebuffer(0, 0, outputWidth, outputHeight, 0, 0, target.width, target.height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);

        GlStateManager.bindTexture(0);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepthTest();
    }

    @Override
    public void close() {
        deleteShared();
        JNI.invokePV(context, destroy);
        library.free();
        GL20C.glDeleteProgram(program);
        GL30C.glDeleteFramebuffers(prepareFramebuffer);
        GL30C.glDeleteFramebuffers(outputFramebuffer);
    }

    private void recreate() {
        deleteShared();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // struct ResizeInfo { int64 renderWidth, renderHeight, outputWidth, outputHeight, quality, preset;
            //   int64 memoryHandles[4], memorySizes[4]; int64 glReady, vkReady; }
            ByteBuffer info = stack.calloc(128);
            info.putLong(0, renderWidth).putLong(8, renderHeight).putLong(16, outputWidth).putLong(24, outputHeight).putLong(32, quality.ngx).putLong(40, model.preset);
            call(resize, info);

            for (int i = 0; i < IMAGES; i++) {
                boolean output = i == OUTPUT;
                long handle = info.getLong(48 + i * 8);
                long size = info.getLong(80 + i * 8);
                memoryObjects[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
                EXTMemoryObject.glMemoryObjectParameteriEXT(memoryObjects[i], EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL11C.GL_TRUE);

                if (WINDOWS) {
                    EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memoryObjects[i], size, EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
                } else {
                    EXTMemoryObjectFD.glImportMemoryFdEXT(memoryObjects[i], size, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) handle);
                }

                textures[i] = GL11C.glGenTextures();
                GlStateManager.bindTexture(textures[i]);
                EXTMemoryObject.glTexStorageMem2DEXT(GL11C.GL_TEXTURE_2D, 1, FORMATS[i], output ? outputWidth : renderWidth, output ? outputHeight : renderHeight, memoryObjects[i], 0);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            }

            glReady = importSemaphore(info.getLong(112));
            vkReady = importSemaphore(info.getLong(120));
        }

        JNI.invokePV(context, closeHandles);

        GlStateManager.bindTexture(0);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prepareFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, textures[COLOR], 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D, textures[DEPTH], 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT2, GL11C.GL_TEXTURE_2D, textures[MOTION], 0);
        GL20C.glDrawBuffers(new int[] {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1, GL30C.GL_COLOR_ATTACHMENT2});
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, outputFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, textures[OUTPUT], 0);
        reset = true;
    }

    private int importSemaphore(long handle) {
        int semaphore = EXTSemaphore.glGenSemaphoresEXT();

        if (WINDOWS) {
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphore, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
        } else {
            EXTSemaphoreFD.glImportSemaphoreFdEXT(semaphore, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, (int) handle);
        }

        return semaphore;
    }

    private void deleteShared() {
        if (textures[0] == 0) {
            return;
        }

        GL11C.glFinish();
        GL11C.glDeleteTextures(textures);
        EXTMemoryObject.glDeleteMemoryObjectsEXT(memoryObjects);
        EXTSemaphore.glDeleteSemaphoresEXT(glReady);
        EXTSemaphore.glDeleteSemaphoresEXT(vkReady);
        Arrays.fill(textures, 0);
    }

    private void call(long function, ByteBuffer info) {
        if (JNI.invokePPI(context, MemoryUtil.memAddress(info), function) == 0) {
            throw new IllegalStateException(lastError());
        }
    }

    private String lastError() {
        return MemoryUtil.memUTF8(JNI.invokePP(context, error));
    }
}
