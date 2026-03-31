package com.potassium.render.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

public final class ShaderProgram implements AutoCloseable {
	private int handle;
	private final String label;

	private ShaderProgram(int handle, String label) {
		this.handle = handle;
		this.label = label;
	}

	public static ShaderProgram graphics(String label, String vertexPath, String fragmentPath) {
		return create(
			label,
			new ShaderSource(GL20C.GL_VERTEX_SHADER, vertexPath),
			new ShaderSource(GL20C.GL_FRAGMENT_SHADER, fragmentPath)
		);
	}

	public static ShaderProgram compute(String label, String computePath) {
		return create(label, new ShaderSource(GL43C.GL_COMPUTE_SHADER, computePath));
	}

	public void use() {
		GL20C.glUseProgram(this.handle);
	}

	public int handle() {
		return this.handle;
	}

	public String label() {
		return this.label;
	}

	@Override
	public void close() {
		if (this.handle != 0) {
			GL20C.glDeleteProgram(this.handle);
			this.handle = 0;
		}
	}

	private static ShaderProgram create(String label, ShaderSource... sources) {
		int program = GL20C.glCreateProgram();
		int[] compiledShaders = new int[sources.length];

		try {
			for (int i = 0; i < sources.length; i++) {
				compiledShaders[i] = compileShader(sources[i]);
				GL20C.glAttachShader(program, compiledShaders[i]);
			}

			GL20C.glLinkProgram(program);
			if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
				String log = GL20C.glGetProgramInfoLog(program);
				throw new IllegalStateException("Failed to link shader program '" + label + "': " + log.strip());
			}

			return new ShaderProgram(program, label);
		} catch (RuntimeException exception) {
			GL20C.glDeleteProgram(program);
			throw exception;
		} finally {
			for (int compiledShader : compiledShaders) {
				if (compiledShader != 0) {
					GL20C.glDetachShader(program, compiledShader);
					GL20C.glDeleteShader(compiledShader);
				}
			}
		}
	}

	private static int compileShader(ShaderSource source) {
		String shaderText = readResource(source.resourcePath());
		int shader = GL20C.glCreateShader(source.shaderType());
		GL20C.glShaderSource(shader, shaderText);
		GL20C.glCompileShader(shader);

		if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
			String log = GL20C.glGetShaderInfoLog(shader);
			GL20C.glDeleteShader(shader);
			throw new IllegalStateException(
				"Failed to compile shader '" + source.resourcePath() + "': " + log.strip()
			);
		}

		return shader;
	}

	private static String readResource(String resourcePath) {
		try (InputStream stream = ShaderProgram.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IllegalStateException("Shader resource not found: " + resourcePath);
			}

			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to read shader resource: " + resourcePath, exception);
		}
	}

	private record ShaderSource(int shaderType, String resourcePath) {
	}
}
