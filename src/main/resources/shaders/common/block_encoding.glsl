#ifndef POTASSIUM_BLOCK_ENCODING_GLSL
#define POTASSIUM_BLOCK_ENCODING_GLSL

const uint POTASSIUM_BLOCK_STATE_ID_MASK = (1u << 20u) - 1u;
const uint POTASSIUM_BLOCK_LIGHT_MASK = (1u << 4u) - 1u;
const uint POTASSIUM_BLOCK_FLAGS_MASK = (1u << 4u) - 1u;
const uint POTASSIUM_BLOCK_BLOCK_LIGHT_SHIFT = 20u;
const uint POTASSIUM_BLOCK_SKY_LIGHT_SHIFT = 24u;
const uint POTASSIUM_BLOCK_FLAGS_SHIFT = 28u;
const uint POTASSIUM_BLOCK_FLAG_OCCLUDES = 1u << 0u;
const uint POTASSIUM_BLOCK_FLAG_FLUID = 1u << 1u;
const uint POTASSIUM_BLOCK_FLAG_TRANSLUCENT = 1u << 2u;

uint potassium_block_state_id(uint packedBlock) {
    return packedBlock & POTASSIUM_BLOCK_STATE_ID_MASK;
}

uint potassium_block_flags(uint packedBlock) {
    return (packedBlock >> POTASSIUM_BLOCK_FLAGS_SHIFT) & POTASSIUM_BLOCK_FLAGS_MASK;
}

uint potassium_block_block_light(uint packedBlock) {
    return (packedBlock >> POTASSIUM_BLOCK_BLOCK_LIGHT_SHIFT) & POTASSIUM_BLOCK_LIGHT_MASK;
}

uint potassium_block_sky_light(uint packedBlock) {
    return (packedBlock >> POTASSIUM_BLOCK_SKY_LIGHT_SHIFT) & POTASSIUM_BLOCK_LIGHT_MASK;
}

bool potassium_block_is_air(uint packedBlock) {
    return potassium_block_state_id(packedBlock) == 0u;
}

bool potassium_block_occludes(uint packedBlock) {
    return (potassium_block_flags(packedBlock) & POTASSIUM_BLOCK_FLAG_OCCLUDES) != 0u;
}

bool potassium_block_is_fluid(uint packedBlock) {
    return (potassium_block_flags(packedBlock) & POTASSIUM_BLOCK_FLAG_FLUID) != 0u;
}

bool potassium_block_is_translucent(uint packedBlock) {
    return (potassium_block_flags(packedBlock) & POTASSIUM_BLOCK_FLAG_TRANSLUCENT) != 0u;
}

#endif
