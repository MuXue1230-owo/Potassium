#version 450 core

layout(location = 0) out vec4 fragColor;

layout(location = 0) flat in uint vPackedBlock;

vec3 potassium_debug_color(uint packedBlock) {
    uint stateId = packedBlock & ((1u << 20u) - 1u);
    uint hash = stateId * 1664525u + 1013904223u;
    float r = float(hash & 255u) / 255.0;
    float g = float((hash >> 8u) & 255u) / 255.0;
    float b = float((hash >> 16u) & 255u) / 255.0;
    return vec3(0.35) + (vec3(r, g, b) * 0.65);
}

void main() {
    fragColor = vec4(potassium_debug_color(vPackedBlock), 0.65);
}
