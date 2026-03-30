struct PotassiumSceneSection {
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
    int facingListLow;
    int facingListHigh;
    int vertexCount0;
    int vertexCount1;
    int vertexCount2;
    int vertexCount3;
    int vertexCount4;
    int vertexCount5;
    int vertexCount6;
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

vec3 _potassium_world_translation(PotassiumGeometrySection geometrySection) {
    return vec3(
        float(geometrySection.sectionChunkX << 4),
        float(geometrySection.sectionChunkY << 4),
        float(geometrySection.sectionChunkZ << 4)
    );
}
