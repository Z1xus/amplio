package us.z1x.amplio;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// asks lucerna through the iris api whether a shader pack renders the world, without linking against it
final class ShaderPacks {
    private static final MethodHandle IN_USE = find();

    private ShaderPacks() {
    }

    static boolean inUse() {
        try {
            return IN_USE != null && (boolean) IN_USE.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    private static MethodHandle find() {
        if (!FabricLoader.getInstance().isModLoaded("lucerna")) {
            return null;
        }

        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
            MethodHandle inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
            return MethodHandles.filterReturnValue(instance, inUse);
        } catch (ReflectiveOperationException e) {
            Amplio.LOGGER.warn("Lucerna is loaded but its shader pack state is unavailable", e);
            return null;
        }
    }
}
