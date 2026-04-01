#version 450 core

layout(binding = 0) uniform sampler2D uAccumulationTexture;
layout(binding = 1) uniform sampler2D uRevealageTexture;

layout(location = 0) in vec2 vTextureUv;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 accumulation = texture(uAccumulationTexture, vTextureUv);
    float revealage = texture(uRevealageTexture, vTextureUv).a;
    float alpha = clamp(1.0 - revealage, 0.0, 1.0);
    if (alpha <= 0.0001) {
        discard;
    }

    vec3 color = accumulation.rgb / max(accumulation.a, 0.0001);
    fragColor = vec4(color, alpha);
}
