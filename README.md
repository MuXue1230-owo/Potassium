# Potassium

Potassium is an independent GPU-driven rendering engine project for Minecraft on Fabric.

Its long-term goal is to move far more world processing, mesh generation, culling, and draw submission onto the GPU than a traditional chunk renderer. The project no longer depends on Sodium and is being built as its own rendering stack.

## Project Status

Potassium is currently a technical preview, not a finished performance mod release.

Today, the project has a working engine bootstrap, OpenGL capability negotiation, GPU-resident world-data upload path, paged world-data storage, and incremental block-change synchronization. The visible terrain renderer has not been replaced yet, so this build should be understood as a backend milestone rather than a finished player-facing renderer.

## What Works Today

- Independent client-side engine startup without Sodium.
- OpenGL startup path with `4.6` preferred and `4.5` minimum.
- Capability checks for SSBOs, persistent mapping, indirect draw, compute shaders, and direct state access.
- GPU-resident world-data storage configured from the active world height.
- Page-based world-data buffer growth, allowing the resident world store to grow beyond the single-buffer `2 GiB` Java mapping limit.
- Chunk serialization and upload into resident GPU slots.
- Chunk unload handling that releases resident storage.
- Incremental per-tick block-change synchronization for chunks that are already resident.
- Placeholder shader pipeline for the upcoming mesh-generation and rendering stages.

## Runtime Expectations

When the game starts, Potassium initializes its own runtime, negotiates the OpenGL context, validates required GPU features, and allocates the baseline rendering resources.

When you enter a world, Potassium inspects the world height layout, configures the resident world-data store for that layout, uploads nearby loaded chunks into GPU memory, expands the world-data buffer when budget and VRAM estimates allow, and applies block updates back into resident storage as the world changes.

This means the backend data path is already active. It does not yet mean that Potassium is drawing the final terrain through its own GPU-driven renderer.

## What Is Not Finished Yet

- Potassium does not yet replace vanilla terrain rendering.
- GPU mesh generation is not active in the visible rendering path.
- Indirect draw submission is not yet driving terrain rendering.
- GPU frustum and occlusion culling are not yet part of final terrain submission.
- Iris compatibility, full shader compatibility, polished configuration UI, and production diagnostics are still in progress.
- Published performance claims are still targets, not validated release numbers.

## Who This Build Is For

This build is currently best suited for:

- engine and rendering development
- technical testing
- early validation of the world-residency pipeline
- contributors who want to help move the renderer toward full GPU-driven terrain rendering

It is not yet recommended as a general-use drop-in performance mod for everyday players.

## Current Technical Snapshot

- Rendering model: independent GPU-driven renderer under active development
- Current milestone: resident world-data backend and synchronization
- World storage model: paged SSBO-backed resident world data
- Expansion model: dynamic growth with VRAM-aware budget checks
- Chunk update model: incremental block-change writes for resident chunks

## Near-Term Priorities

1. Connect dirty resident chunks to real GPU mesh generation.
2. Define the paged shader-side addressing model for world data.
3. Build indirect draw command generation and GPU culling into the visible terrain path.
4. Add streaming and eviction so large worlds stay within stable memory budgets.

## Platform Requirements

- Minecraft: `26.1`
- Fabric Loader: `0.18.5+`
- Java: `25+`
- OpenGL: `4.5+`
- Operating systems: Windows and Linux

Development builds in this workspace are currently validated with Java `26`.

## Build

```bash
./gradlew build
```

## License

`LGPL-3.0`
