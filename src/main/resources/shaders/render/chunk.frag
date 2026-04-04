#version 450 core

layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D uBlockAtlas;

layout(location = 0) in vec2 vUv;
layout(location = 1) in float vShade;
layout(location = 2) flat in uint vPackedBlock;
layout(location = 3) flat in int vTintIndex;

void main() {
    vec4 atlasColor = texture(uBlockAtlas, vUv);
    if (atlasColor.a <= 0.0) {
        discard;
    }

    vec3 litColor = atlasColor.rgb * vShade;
    fragColor = vec4(litColor, atlasColor.a);
}
