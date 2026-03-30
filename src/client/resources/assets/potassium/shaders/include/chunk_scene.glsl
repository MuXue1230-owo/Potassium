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

struct PotassiumGeometrySection {
    int geometrySourceId;
    int regionSceneId;
    int sectionSceneId;
    int localSectionIndex;
    int flags;
    int sectionChunkX;
    int sectionChunkY;
    int sectionChunkZ;
    int sliceMask;
    int baseElement;
    int baseVertex;
    int regionSlot;
};

layout(std430, binding = 4) readonly buffer PotassiumSceneData {
    PotassiumSceneSection u_PotassiumSceneSections[];
};

layout(std430, binding = 5) readonly buffer PotassiumGeometryData {
    PotassiumGeometrySection u_PotassiumGeometrySections[];
};

bool _potassium_use_scene_data() {
    return gl_BaseInstance != 0;
}

PotassiumSceneSection _potassium_get_scene_section() {
    return u_PotassiumSceneSections[gl_BaseInstance];
}

PotassiumGeometrySection _potassium_get_geometry_section() {
    return u_PotassiumGeometrySections[gl_BaseInstance];
}

uvec3 _potassium_relative_chunk_coord(int localSectionIndex) {
    return uvec3(uint(localSectionIndex)) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _potassium_draw_translation(PotassiumSceneSection sceneSection) {
    return _potassium_relative_chunk_coord(sceneSection.localSectionIndex) * vec3(16.0);
}

vec3 _potassium_world_translation(PotassiumSceneSection sceneSection) {
    return vec3(
        float(sceneSection.sectionChunkX << 4),
        float(sceneSection.sectionChunkY << 4),
        float(sceneSection.sectionChunkZ << 4)
    );
}

vec3 _potassium_world_translation(PotassiumGeometrySection geometrySection) {
    return vec3(
        float(geometrySection.sectionChunkX << 4),
        float(geometrySection.sectionChunkY << 4),
        float(geometrySection.sectionChunkZ << 4)
    );
}
