#version 450 core

#include "chunk_debug_common.glsl"

layout(location = 0) out vec4 fragColor;

layout(location = 0) flat in uint vPackedBlock;

void main() {
    fragColor = vec4(potassium_debug_color(vPackedBlock), potassium_block_alpha(vPackedBlock));
}
