#version 450 core

#include "../common/material_table.glsl"

layout(location = 0) in ivec4 aPackedVertex;
layout(location = 1) in uvec2 aPackedSurface;

layout(location = 0) uniform mat4 uModelViewMatrix;
layout(location = 4) uniform mat4 uProjectionMatrix;

layout(location = 0) out vec2 vUv;
layout(location = 1) out float vShade;
layout(location = 2) flat out uint vPackedBlock;
layout(location = 3) flat out int vTintIndex;

float potassium_face_shade(uint faceId) {
    switch (faceId) {
        case 0u:
            return 1.0;
        case 1u:
            return 0.6;
        case 2u:
        case 3u:
            return 0.8;
        default:
            return 0.9;
    }
}

void main() {
    vec3 worldPosition = vec3(aPackedVertex.xyz);
    gl_Position = uProjectionMatrix * uModelViewMatrix * vec4(worldPosition, 1.0);
    vPackedBlock = uint(aPackedVertex.w);

    uint faceData = aPackedSurface.x;
    uint lightData = aPackedSurface.y;
    uint faceId = faceData & 7u;
    uint cornerIndex = (faceData >> 3u) & 3u;
    uint stateId = potassium_block_state_id(vPackedBlock);
    vec4 uvRect = potassium_material_uv_rect(stateId, faceId);
    vUv = potassium_material_corner_uv(uvRect, cornerIndex);
    vTintIndex = potassium_material_tint_index(stateId, faceId);

    float blockLight = float(lightData & 0xFu) / 15.0;
    float skyLight = float((lightData >> 4u) & 0xFu) / 15.0;
    float ao = float((lightData >> 8u) & 0xFFu) / 255.0;
    float lighting = max(blockLight, skyLight * 0.85);
    if (potassium_material_shade(stateId, faceId)) {
        lighting *= potassium_face_shade(faceId);
    }
    if (potassium_material_uses_ao(stateId, faceId)) {
        lighting *= ao;
    }
    vShade = max(lighting, 0.08);
}
