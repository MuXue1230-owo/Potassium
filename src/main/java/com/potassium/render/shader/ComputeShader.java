package com.potassium.render.shader;

public final class ComputeShader implements AutoCloseable {
	private final ShaderProgram program;

	private ComputeShader(ShaderProgram program) {
		this.program = program;
	}

	public static ComputeShader load(String label, String resourcePath) {
		return new ComputeShader(ShaderProgram.compute(label, resourcePath));
	}

	public void use() {
		this.program.use();
	}

	public int handle() {
		return this.program.handle();
	}

	@Override
	public void close() {
		this.program.close();
	}
}
