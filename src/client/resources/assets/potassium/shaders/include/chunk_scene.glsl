struct PotassiumSceneSection {
    int regionSceneId;
    int sectionSceneId;
    int localSectionIndex;
    int flags;
    int sliceMask;
    int sectionChunkX;
    int sectionChunkY;
    int sectionChunkZ;
    vec4 centerAndRadius;
    vec4 dynamicData;
};

layout(std430, binding = 4) readonly buffer PotassiumSceneData {
    PotassiumSceneSection u_PotassiumSceneSections[];
};

bool _potassium_use_scene_data() {
    return gl_BaseInstance != 0;
}

PotassiumSceneSection _potassium_get_scene_section() {
    return u_PotassiumSceneSections[gl_BaseInstance];
}

uvec3 _potassium_relative_chunk_coord(int localSectionIndex) {
    return uvec3(uint(localSectionIndex)) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _potassium_draw_translation(PotassiumSceneSection sceneSection) {
    return _potassium_relative_chunk_coord(sceneSection.localSectionIndex) * vec3(16.0);
}
