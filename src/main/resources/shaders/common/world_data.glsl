#version 450 core

struct PotassiumBlockData {
    uint packed;
};

layout(std430, binding = 0) readonly buffer PotassiumWorldDataBuffer {
    PotassiumBlockData blocks[];
} potassium_world_data;
