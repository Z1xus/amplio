package us.z1x.amplio;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class AmplioConfig {
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("amplio.properties");

	public UpscalerType upscaler = UpscalerType.FSR1;
	// render resolution relative to the window, per axis
	public float scale = 0.5F;
	// rcas sharpness in stops, 0 is the sharpest
	public float sharpness = 0.25F;

	public static AmplioConfig load() {
		AmplioConfig config = new AmplioConfig();

		if (Files.exists(PATH)) {
			Properties properties = new Properties();

			try (Reader reader = Files.newBufferedReader(PATH)) {
				properties.load(reader);
				config.upscaler = UpscalerType.valueOf(properties.getProperty("upscaler", config.upscaler.name()));
				config.scale = Math.clamp(Float.parseFloat(properties.getProperty("scale", "0.5")), 0.25F, 1.0F);
				config.sharpness = Math.clamp(Float.parseFloat(properties.getProperty("sharpness", "0.25")), 0.0F, 2.0F);
			} catch (IOException | IllegalArgumentException e) {
				Amplio.LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			}
		}

		return config;
	}

	public void save() {
		Properties properties = new Properties();
		properties.setProperty("upscaler", upscaler.name());
		properties.setProperty("scale", Float.toString(scale));
		properties.setProperty("sharpness", Float.toString(sharpness));

		try (Writer writer = Files.newBufferedWriter(PATH)) {
			properties.store(writer, null);
		} catch (IOException e) {
			Amplio.LOGGER.warn("Failed to save {}", PATH, e);
		}
	}
}
