package us.z1x.amplio.upscale;

import org.lwjgl.opengl.GL20C;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class Shaders {
	private static final String ROOT = "/assets/amplio/shaders/";

	private Shaders() {
	}

	// glsl 4.10 is the macos limit, #include lines are resolved inline
	static int link(String vertex, String fragment) {
		int program = GL20C.glCreateProgram();
		int vs = compile(GL20C.GL_VERTEX_SHADER, read(vertex));
		int fs = compile(GL20C.GL_FRAGMENT_SHADER, "#version 410\n" + resolve(read(fragment)));
		GL20C.glAttachShader(program, vs);
		GL20C.glAttachShader(program, fs);
		GL20C.glLinkProgram(program);
		GL20C.glDeleteShader(vs);
		GL20C.glDeleteShader(fs);

		if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
			throw new IllegalStateException("Failed to link " + fragment + ": " + GL20C.glGetProgramInfoLog(program));
		}

		return program;
	}

	private static int compile(int type, String source) {
		int shader = GL20C.glCreateShader(type);
		GL20C.glShaderSource(shader, source);
		GL20C.glCompileShader(shader);

		if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
			throw new IllegalStateException("Failed to compile shader: " + GL20C.glGetShaderInfoLog(shader));
		}

		return shader;
	}

	private static String resolve(String source) {
		StringBuilder out = new StringBuilder();

		for (String line : source.split("\n", -1)) {
			out.append(line.startsWith("#include \"") ? resolve(read(line.substring(10, line.length() - 1))) : line).append('\n');
		}

		return out.toString();
	}

	private static String read(String name) {
		try (InputStream in = Shaders.class.getResourceAsStream(ROOT + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing shader " + name);
			}

			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
