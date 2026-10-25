package us.z1x.amplio.upscale;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// copies the bundled native libraries for this platform out of the jar, once per mod version
final class Natives {
    private Natives() {
    }

    static Path extract() throws IOException {
        String platform = platform();
        String root = "/natives/" + platform + "/";
        String version = FabricLoader.getInstance().getModContainer("amplio").orElseThrow().getMetadata().getVersion().getFriendlyString();
        Path directory = FabricLoader.getInstance().getGameDir().resolve("amplio").resolve("natives").resolve(version).resolve(platform);

        try (InputStream index = Natives.class.getResourceAsStream(root + "index")) {
            if (index == null) {
                throw new IOException("No natives for " + platform);
            }

            Files.createDirectories(directory);

            for (String name : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                Path file = directory.resolve(name.strip());

                if (name.isBlank() || Files.exists(file)) {
                    continue;
                }

                try (InputStream in = Natives.class.getResourceAsStream(root + name.strip())) {
                    Path temporary = Files.createTempFile(directory, name.strip(), ".tmp");
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }

        return directory;
    }

    static String library() {
        return Platform.get() == Platform.WINDOWS ? "amplio.dll" : Platform.get() == Platform.MACOSX ? "libamplio.dylib" : "libamplio.so";
    }

    private static String platform() {
        String os = switch (Platform.get()) {
            case WINDOWS -> "windows";
            case MACOSX -> "macos";
            default -> "linux";
        };
        return os + "-" + (Platform.getArchitecture() == Platform.Architecture.ARM64 ? "arm64" : "x86_64");
    }
}
