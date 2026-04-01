#version 450 core

layout(location = 0) in ivec4 aPackedVertex;

layout(location = 0) uniform mat4 uModelViewMatrix;
layout(location = 4) uniform mat4 uProjectionMatrix;

layout(location = 0) flat out uint vPackedBlock;

void main() {
    vec3 worldPosition = vec3(aPackedVertex.xyz);
    worldPosition.y += 0.025;
    gl_Position = uProjectionMatrix * uModelViewMatrix * vec4(worldPosition, 1.0);
    vPackedBlock = uint(aPackedVertex.w);
}
