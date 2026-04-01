package com.potassium.render.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

public final class ShaderProgram implements AutoCloseable {
	private static final Pattern INCLUDE_PATTERN = Pattern.compile("^\\s*#include\\s+[\"<]([^\">]+)[\">]\\s*$");

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
		return readResource(resourcePath, new HashSet<>());
	}

	private static String readResource(String resourcePath, Set<String> includeStack) {
		String normalizedPath = normalizeResourcePath(resourcePath);
		if (!includeStack.add(normalizedPath)) {
			throw new IllegalStateException("Shader include cycle detected at: " + normalizedPath);
		}

		try (InputStream stream = ShaderProgram.class.getClassLoader().getResourceAsStream(normalizedPath)) {
			if (stream == null) {
				throw new IllegalStateException("Shader resource not found: " + resourcePath);
			}

			StringBuilder resolvedSource = new StringBuilder();
			String resourceText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			String baseDirectory = resourceDirectory(normalizedPath);
			String[] lines = resourceText.split("\\R", -1);
			for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
				String line = lines[lineIndex];
				Matcher matcher = INCLUDE_PATTERN.matcher(line);
				if (matcher.matches()) {
					String includePath = resolveIncludePath(baseDirectory, matcher.group(1));
					resolvedSource.append(readResource(includePath, includeStack));
				} else {
					resolvedSource.append(line);
				}

				if (lineIndex < lines.length - 1) {
					resolvedSource.append('\n');
				}
			}

			return resolvedSource.toString();
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to read shader resource: " + resourcePath, exception);
		} finally {
			includeStack.remove(normalizedPath);
		}
	}

	private static String resolveIncludePath(String baseDirectory, String includePath) {
		if (includePath.startsWith("shaders/")) {
			return normalizeResourcePath(includePath);
		}

		return normalizeResourcePath(baseDirectory + includePath);
	}

	private static String resourceDirectory(String resourcePath) {
		int lastSeparator = resourcePath.lastIndexOf('/');
		return lastSeparator >= 0 ? resourcePath.substring(0, lastSeparator + 1) : "";
	}

	private static String normalizeResourcePath(String resourcePath) {
		String normalized = resourcePath.replace('\\', '/');
		if (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}

		String[] segments = normalized.split("/");
		String[] normalizedSegments = new String[segments.length];
		int segmentCount = 0;
		for (String segment : segments) {
			if (segment.isEmpty() || ".".equals(segment)) {
				continue;
			}
			if ("..".equals(segment)) {
				if (segmentCount == 0) {
					throw new IllegalStateException("Shader include escaped the resource root: " + resourcePath);
				}
				segmentCount--;
				continue;
			}
			normalizedSegments[segmentCount++] = segment;
		}

		return String.join("/", java.util.Arrays.copyOf(normalizedSegments, segmentCount));
	}

	private record ShaderSource(int shaderType, String resourcePath) {
	}
}
