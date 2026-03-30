#version 460 core

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <potassium:include/chunk_scene.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

flat out uint v_Material;

#ifdef USE_FOG
out vec2 v_FragDistance;
out float fadeFactor;
#endif

uniform vec3 u_RegionOffset;
uniform vec3 u_CameraPosition;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex;

uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    bool useSceneData = _potassium_use_scene_data();
    PotassiumSceneSection sceneSection = useSceneData
        ? _potassium_get_scene_section()
        : PotassiumSceneSection(0, 0, int(_draw_id), 0, 0, 0, 0, 0, vec4(0.0), vec4(1.0, 0.0, 0.0, 0.0));
    PotassiumGeometrySection geometrySection = useSceneData
        ? _potassium_get_geometry_section()
        : PotassiumGeometrySection(0, 0, int(_draw_id), int(_draw_id), 0, 0, 0, 0, 0, 0, 0, 0);
    vec3 translation = useSceneData
        ? (_potassium_world_translation(geometrySection) - u_CameraPosition)
        : (u_RegionOffset + _get_draw_translation(_draw_id));
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);
    fadeFactor = 1.0;
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
    v_Material = _material_params;
}
