#version 450 core

#include "chunk_debug_common.glsl"

layout(location = 0) out vec4 accumulationOutput;
layout(location = 1) out vec4 revealageOutput;

layout(location = 0) flat in uint vPackedBlock;

void main() {
    float alpha = potassium_block_alpha(vPackedBlock);
    if (alpha <= 0.0) {
        discard;
    }

    vec3 color = potassium_debug_color(vPackedBlock);
    float weight = clamp((alpha * 4.0) + 0.01, 0.01, 8.0);
    accumulationOutput = vec4(color * alpha, alpha) * weight;
    revealageOutput = vec4(0.0, 0.0, 0.0, alpha);
}
