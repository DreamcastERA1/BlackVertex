#version 330

// BlackVertex GPU skinning — 26.1.2 variant. Identical to the 26.2 vsh except bone indices arrive as
// NORMALIZED bytes (vec4 in [0,1]) rather than a uint attribute, sidestepping integer-vertex-attribute
// uncertainty on this version; they are recovered to integers below. Fragment side is a vanilla copy.

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;      // bind-pose, model space
in vec2 UV0;
in vec3 Normal;        // bind-pose, model space
in vec4 BoneIndices;   // normalized bytes; recovered to integers below
in vec4 BoneWeights;   // normalized, sum == 1

uniform sampler2D Sampler1; // overlay
#ifndef EMISSIVE
uniform sampler2D Sampler2; // lightmap
#endif

layout(std140) uniform BonePalette {
    mat4 Bones[MAX_BONES];
    mat4 ModelMat;         // attachment pose: model -> camera-relative space (no camera rotation)
    ivec4 LightAndOverlay; // xy = packed lightmap coords, zw = packed overlay coords
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
#ifndef EMISSIVE
out vec4 lightMapColor;
#endif
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    ivec4 bi = ivec4(BoneIndices * 255.0 + 0.5);
    mat4 skin = Bones[bi.x] * BoneWeights.x
              + Bones[bi.y] * BoneWeights.y
              + Bones[bi.z] * BoneWeights.z
              + Bones[bi.w] * BoneWeights.w;

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
