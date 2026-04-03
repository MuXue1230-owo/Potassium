#version 450 core

const vec2 POTASSIUM_PROBE_TRIANGLE[3] = vec2[](
    vec2(-0.75, -0.60),
    vec2(0.75, -0.60),
    vec2(0.0, 0.80)
);

void main() {
    vec2 position = POTASSIUM_PROBE_TRIANGLE[gl_VertexID];
    gl_Position = vec4(position, 0.0, 1.0);
}
