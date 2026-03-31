# Potassium

Potassium is an independent GPU-driven rendering engine project for Minecraft on Fabric. It no longer depends on Sodium.

## Status

- Version: `v0.0.1`
- Stage: `Phase 1 Early`
- Current focus: build a stable resident world-data pipeline on the GPU before real GPU meshing and indirect rendering replace vanilla rendering

## What Works Today

### Independent engine bootstrap

- All Sodium dependencies, mixins, compat code, shaders, and config hooks have been removed.
- Potassium now initializes as its own client-side engine entrypoint.
- Startup initializes:
  - config loading
  - OpenGL context negotiation
  - OpenGL capability checks
  - optional OpenGL debug output
  - the current render pipeline skeleton

### OpenGL context and capability handling

- Potassium prefers an `OpenGL 4.6` context.
- If `4.6` creation fails, it falls back to `OpenGL 4.5`.
- If the system cannot provide at least `OpenGL 4.5`, startup fails hard.
- The current build checks:
  - shader storage buffer objects
  - persistent mapped buffers
  - indirect draw support
  - indirect count support
  - compute shader support
  - direct state access
  - debug output support

### Shader loading and compilation

- `ShaderProgram` and `ComputeShader` are implemented.
- The current runtime compiles placeholder shaders for:
  - chunk vertex/fragment rendering
  - mesh generation compute
  - frustum culling compute
  - occlusion culling compute

### Resident world-data pipeline

- A dedicated world-data SSBO layout exists.
- `WorldDataBuffer` is configured dynamically from the active level height.
- Chunk data is serialized from `LevelChunk` into packed block data and uploaded into fixed resident slots in GPU memory.
- Chunk unload events release resident slots.
- A fixed memory-budget model is in place for resident chunk storage.

### Incremental block update sync

- Client-side block updates are tracked every tick.
- For chunks that are already resident in the GPU world buffer, block changes are written back directly to the matching buffer offset.
- The current block payload is a packed `uint` containing block-state-oriented data.

### Event hooks already connected

- OpenGL window hint override
- OpenGL context fallback path
- chunk load hook
- chunk unload hook
- block change hook
- world render begin/end hook
- client tick hook

## Runtime Expectations

### On game startup

You should expect the following:

- Potassium installs its own client bootstrap path.
- The game attempts to create an OpenGL 4.6 context first, then 4.5 if needed.
- Potassium logs OpenGL version, vendor, renderer, and capability details.
- The render pipeline allocates its baseline GPU buffers and compiles placeholder shaders.

### After entering a world

You should expect the following:

- Potassium detects the active world height layout and configures the resident world-data buffer for that layout.
- Already loaded chunks near the player are queued for upload.
- The current loader uploads up to `8` chunks per tick.
- The current unload path releases up to `32` chunks per tick.
- Block changes are queued as incremental sync work.
- If a changed block belongs to a resident chunk, the corresponding GPU buffer entry is updated directly.

### What you should not expect yet

This build does **not** yet provide the end-user rendering results the final project is targeting:

- it does not replace vanilla terrain rendering yet
- it does not perform real GPU mesh generation yet
- it does not reduce draw calls to the final target range yet
- it does not provide validated FPS improvements yet
- it does not perform full GPU frustum or occlusion culling for actual terrain submission yet
- it does not include a complete debug overlay or config UI yet

In short: the world-data path into GPU memory exists, but the full GPU-driven rendering path does not exist yet.

## Current Limitations

- This is still a development build, not a usable performance mod release.
- Resident world storage currently uses a fixed slot allocator, not a full streaming or eviction system.
- The world buffer currently stores packed block data only.
- The following systems are still placeholders or skeletons:
  - GPU meshing
  - LOD selection
  - indirect draw submission
  - real frustum/occlusion driven draw generation
  - shader compatibility layers
  - Iris compatibility
  - config screen
  - polished debug overlay

## Key Files

### Core

- `src/main/java/com/potassium/core/PotassiumEngine.java`
- `src/main/java/com/potassium/core/PotassiumConfig.java`
- `src/main/java/com/potassium/core/PotassiumLogger.java`

### OpenGL and buffers

- `src/main/java/com/potassium/gl/GLCapabilities.java`
- `src/main/java/com/potassium/gl/GLDebug.java`
- `src/main/java/com/potassium/gl/buffer/PersistentBuffer.java`
- `src/main/java/com/potassium/gl/buffer/WorldDataBuffer.java`
- `src/main/java/com/potassium/gl/buffer/IndirectCommandBuffer.java`

### World data

- `src/main/java/com/potassium/world/ChunkLoader.java`
- `src/main/java/com/potassium/world/ChunkManager.java`
- `src/main/java/com/potassium/world/MemoryManager.java`
- `src/main/java/com/potassium/world/WorldChangeTracker.java`
- `src/main/java/com/potassium/world/data/ChunkSerializer.java`
- `src/main/java/com/potassium/world/data/ChunkSnapshot.java`
- `src/main/resources/shaders/common/world_data.glsl`

### Mixins

- `src/main/java/com/potassium/mixin/WindowContextMixin.java`
- `src/main/java/com/potassium/mixin/WindowContextFallbackMixin.java`
- `src/main/java/com/potassium/mixin/ChunkLifecycleMixin.java`
- `src/main/java/com/potassium/mixin/BlockChangeMixin.java`
- `src/main/java/com/potassium/mixin/WorldRenderMixin.java`
- `src/main/java/com/potassium/mixin/MinecraftClientMixin.java`

## Requirements

- Minecraft: `26.1`
- Fabric Loader: `0.18.5+`
- Java: `25+`
- OpenGL: `4.5+`
- Operating systems: Windows and Linux are the intended targets

## Build

```bash
./gradlew build
```

Current development builds in this workspace have been validated using Java 26.

## Next Steps

The next high-value milestones are:

1. feed dirty resident chunks into actual GPU mesh generation
2. generate terrain meshes via compute shaders
3. build indirect draw command generation and GPU culling
4. replace the visible terrain rendering path with the new pipeline
