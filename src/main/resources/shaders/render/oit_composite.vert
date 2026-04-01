#version 450 core

layout(location = 0) out vec2 vTextureUv;

const vec2 POTASSIUM_FULLSCREEN_TRIANGLE[3] = vec2[](
    vec2(-1.0, -1.0),
    vec2(3.0, -1.0),
    vec2(-1.0, 3.0)
);

void main() {
    vec2 position = POTASSIUM_FULLSCREEN_TRIANGLE[gl_VertexID];
    gl_Position = vec4(position, 0.0, 1.0);
    vTextureUv = position * 0.5 + 0.5;
}
