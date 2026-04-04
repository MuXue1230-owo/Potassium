#version 450 core

layout(location = 0) out vec4 accumulationOutput;
layout(location = 1) out vec4 revealageOutput;

layout(binding = 0) uniform sampler2D uBlockAtlas;

layout(location = 0) in vec2 vUv;
layout(location = 1) in float vShade;
layout(location = 2) flat in uint vPackedBlock;
layout(location = 3) flat in int vTintIndex;

void main() {
    vec4 atlasColor = texture(uBlockAtlas, vUv);
    float alpha = atlasColor.a;
    if (alpha <= 0.0) {
        discard;
    }

    vec3 color = atlasColor.rgb * vShade;
    float weight = clamp((alpha * 4.0) + 0.01, 0.01, 8.0);
    accumulationOutput = vec4(color * alpha, alpha) * weight;
    revealageOutput = vec4(0.0, 0.0, 0.0, alpha);
}
