#define POTASSIUM_BLOCK_FLAGS_MASK ((1u << 4u) - 1u)
#define POTASSIUM_BLOCK_FLAGS_SHIFT 28u
#define POTASSIUM_BLOCK_FLAG_FLUID (1u << 1u)
#define POTASSIUM_BLOCK_FLAG_TRANSLUCENT (1u << 2u)

vec3 potassium_debug_color(uint packedBlock) {
    uint stateId = packedBlock & ((1u << 20u) - 1u);
    uint hash = stateId * 1664525u + 1013904223u;
    float r = float(hash & 255u) / 255.0;
    float g = float((hash >> 8u) & 255u) / 255.0;
    float b = float((hash >> 16u) & 255u) / 255.0;
    return vec3(0.35) + (vec3(r, g, b) * 0.65);
}

uint potassium_block_flags(uint packedBlock) {
    return (packedBlock >> POTASSIUM_BLOCK_FLAGS_SHIFT) & POTASSIUM_BLOCK_FLAGS_MASK;
}

float potassium_block_alpha(uint packedBlock) {
    uint flags = potassium_block_flags(packedBlock);
    if ((flags & POTASSIUM_BLOCK_FLAG_FLUID) != 0u) {
        return 0.4;
    }
    if ((flags & POTASSIUM_BLOCK_FLAG_TRANSLUCENT) != 0u) {
        return 0.6;
    }

    return 1.0;
}
