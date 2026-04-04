#ifndef POTASSIUM_MATERIAL_TABLE_GLSL
#define POTASSIUM_MATERIAL_TABLE_GLSL

#include "block_encoding.glsl"

const uint POTASSIUM_MATERIAL_TABLE_FACES = 6u;
const uint POTASSIUM_MATERIAL_TABLE_WORDS_PER_FACE = 6u;
const uint POTASSIUM_MATERIAL_TABLE_WORDS_PER_STATE = POTASSIUM_MATERIAL_TABLE_FACES * POTASSIUM_MATERIAL_TABLE_WORDS_PER_FACE;
const uint POTASSIUM_MATERIAL_FLAG_SHADE = 1u << 0u;
const uint POTASSIUM_MATERIAL_FLAG_USE_AO = 1u << 1u;
const uint POTASSIUM_MATERIAL_FLAG_TINTED = 1u << 2u;
const uint POTASSIUM_MATERIAL_FLAG_LAYER_CUTOUT = 1u << 3u;
const uint POTASSIUM_MATERIAL_FLAG_LAYER_TRANSLUCENT = 1u << 4u;

layout(std430, binding = 13) readonly buffer PotassiumMaterialTableBuffer {
    uint materialWords[];
} potassium_material_table;

uint potassium_material_face_base(uint stateId, uint faceId) {
    return (stateId * POTASSIUM_MATERIAL_TABLE_WORDS_PER_STATE) + (faceId * POTASSIUM_MATERIAL_TABLE_WORDS_PER_FACE);
}

vec4 potassium_material_uv_rect(uint stateId, uint faceId) {
    uint base = potassium_material_face_base(stateId, faceId);
    return vec4(
        uintBitsToFloat(potassium_material_table.materialWords[base + 0u]),
        uintBitsToFloat(potassium_material_table.materialWords[base + 1u]),
        uintBitsToFloat(potassium_material_table.materialWords[base + 2u]),
        uintBitsToFloat(potassium_material_table.materialWords[base + 3u])
    );
}

int potassium_material_tint_index(uint stateId, uint faceId) {
    return int(potassium_material_table.materialWords[potassium_material_face_base(stateId, faceId) + 4u]);
}

uint potassium_material_flags(uint stateId, uint faceId) {
    return potassium_material_table.materialWords[potassium_material_face_base(stateId, faceId) + 5u];
}

bool potassium_material_shade(uint stateId, uint faceId) {
    return (potassium_material_flags(stateId, faceId) & POTASSIUM_MATERIAL_FLAG_SHADE) != 0u;
}

bool potassium_material_uses_ao(uint stateId, uint faceId) {
    return (potassium_material_flags(stateId, faceId) & POTASSIUM_MATERIAL_FLAG_USE_AO) != 0u;
}

bool potassium_material_is_cutout(uint stateId, uint faceId) {
    return (potassium_material_flags(stateId, faceId) & POTASSIUM_MATERIAL_FLAG_LAYER_CUTOUT) != 0u;
}

bool potassium_material_is_translucent(uint stateId, uint faceId) {
    return (potassium_material_flags(stateId, faceId) & POTASSIUM_MATERIAL_FLAG_LAYER_TRANSLUCENT) != 0u;
}

vec2 potassium_material_corner_uv(vec4 uvRect, uint cornerIndex) {
    switch (cornerIndex) {
        case 0u:
            return vec2(uvRect.x, uvRect.w);
        case 1u:
            return vec2(uvRect.x, uvRect.z);
        case 2u:
            return vec2(uvRect.y, uvRect.z);
        default:
            return vec2(uvRect.y, uvRect.w);
    }
}

#endif
