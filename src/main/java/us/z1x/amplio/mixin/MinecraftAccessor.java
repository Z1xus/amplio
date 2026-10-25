package us.z1x.amplio.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("renderTarget")
    void amplio$setRenderTarget(RenderTarget target);
}
