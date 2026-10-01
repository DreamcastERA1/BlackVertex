#version 330
#extension GL_ARB_separate_shader_objects : require

// BlackVertex GPU skinning: bind-pose vertices + bone palette UBO, blended on the GPU.
// Companion of the vanilla entity shader pair — the fragment side is a vanilla copy,
// so lighting/fog/cutout behave exactly like entityCutout. Output locations match its inputs.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;      // bind-pose, model space
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec3 Normal;        // bind-pose, model space
layout(location = 3) in uvec4 BoneIndices;
layout(location = 4) in vec4 BoneWeights;   // normalized, sum == 1

uniform sampler2D Sampler1; // overlay
#ifndef EMISSIVE
uniform sampler2D Sampler2; // lightmap
#endif

layout(std140) uniform BonePalette {
    mat4 Bones[MAX_BONES];
    mat4 ModelMat;         // attachment pose: model -> camera-relative space (no camera rotation)
    ivec4 LightAndOverlay; // xy = packed lightmap coords, zw = packed overlay coords
};

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
#ifndef EMISSIVE
layout(location = 4) out vec4 lightMapColor;
#endif
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord0;

void main() {
    mat4 skin = Bones[BoneIndices.x] * BoneWeights.x
              + Bones[BoneIndices.y] * BoneWeights.y
              + Bones[BoneIndices.z] * BoneWeights.z
              + Bones[BoneIndices.w] * BoneWeights.w;

    // Mirrors the vanilla entity path exactly: ModelMat plays the role of the CPU-side pose
    // transform (positions land in camera-relative space, like vanilla's pre-baked vertices),
    // and the global ModelViewMat (camera rotation) applies on top, same as vanilla's shader.
    vec4 posedPos = ModelMat * (skin * vec4(Position, 1.0));
    gl_Position = ProjMat * ModelViewMat * posedPos;

    // Vanilla feeds the pre-ModelView position to fog, so we do too.
    sphericalVertexDistance = fog_spherical_distance(posedPos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(posedPos.xyz);

#ifdef EMISSIVE
    vertexColor = vec4(1.0); // fullbright: no cardinal lighting, no lightmap
#else
    // Vanilla normals get the pose rotation only (setNormal(pose, ...)), not the camera's.
    vec3 normal = normalize(mat3(ModelMat) * (mat3(skin) * Normal));
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, vec4(1.0));
    lightMapColor = sample_lightmap(Sampler2, LightAndOverlay.xy);
#endif
    overlayColor = texelFetch(Sampler1, LightAndOverlay.zw, 0);
    texCoord0 = UV0;
}
