# Potassium

<div align="center">

![Potassium Logo](https://via.placeholder.com/128x128/8B5CF6/FFFFFF?text=K)

**Advanced Indirect Rendering Backend Extension for Sodium**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1-green?style=flat-square)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-Latest-blue?style=flat-square)](https://fabricmc.net)
[![OpenGL](https://img.shields.io/badge/OpenGL-4.5+-red?style=flat-square)](https://opengl.org)
[![License](https://img.shields.io/badge/License-LGPL--3.0-orange?style=flat-square)](LICENSE)

[Download](../../releases) · [Wiki](../../wiki) · [Issues](../../issues)

</div>

---

## 📖 Introduction

**Potassium (K)** is an advanced extension module designed for the [Sodium](https://github.com/CaffeineMC/sodium-fabric) rendering engine. It leverages modern OpenGL (4.5+) features to implement **Indirect Drawing** and **GPU Driven Rendering**, aiming to突破 the rendering performance bottlenecks of Minecraft Java Edition.

### 🧪 Naming Origin

| Element | Symbol | Role |
| :--- | :--- | :--- |
| **Sodium** | Na | Base rendering optimization, high-performance foundation |
| **Potassium** | K | **This Mod**: Provides more "reactive" advanced rendering features on top of Sodium |
| **Lithium** | Li | Logic layer optimization (physics/entities), complementary to rendering |

> 💡 **Chemistry Pun**: Potassium lies below Sodium in the periodic table, both belonging to **alkali metals**. Potassium is more chemically reactive—just as this mod introduces more aggressive GPU features on top of Sodium!

---

## ✨ Core Features

### 🚀 Performance Improvements

| Feature | Description | Expected Gain |
| :--- | :--- | :--- |
| **Indirect Drawing** | Batch submit draw commands using `glMultiDrawArraysIndirect` | Draw Calls reduced 10-100x |
| **GPU Culling** | Frustum/Occlusion culling on GPU using Compute Shaders | CPU rendering overhead reduced 50%+ |
| **Persistent Mapping** | Reduce CPU-GPU sync wait using `glBufferStorage` | More stable frame times, less stutter |
| **DSA Support** | Direct State Access reduces state binding overhead | More efficient code, lower driver overhead |

### 📊 Performance Comparison (Expected)

| Scenario | Sodium Native | Potassium | Improvement |
| :--- | :--- | :--- | :--- |
| Chunk Render Draw Calls | ~500/frame | ~5/frame | **99% ↓** |
| CPU Render Time | ~8ms | ~2ms | **75% ↓** |
| Ultra Far View (32+ chunks) | 30-40 FPS | 60-80 FPS | **2x+** |
| Complex Terrain Frame Variance | ±15 FPS | ±5 FPS | **More Stable** |

> ⚠️ Data above is theoretical expectation. Actual performance depends on hardware configuration and game scene.

---

## 🖥️ System Requirements

### Hard Requirements (Will not run if not met)

| Component | Requirement | Note |
| :--- | :--- | :--- |
| **Minecraft** | 1.20.4+ | Based on Fabric Loader |
| **Java** | 17+ | 64-bit JVM required |
| **Sodium** | 0.5.0+ | Must be installed as a dependency |
| **OpenGL** | **4.5+** | **Core requirement, NO fallback** |
| **OS** | Windows / Linux | **macOS NOT supported** |

### Recommended Configuration

| Component | Recommended |
| :--- | :--- |
| **GPU** | NVIDIA GTX 1060+ / AMD RX 580+ / Intel Arc A380+ |
| **VRAM** | 4GB+ |
| **Driver** | Latest version (released after 2023) |
| **RAM** | 8GB+ (4GB+ allocated to Minecraft) |

### ❌ Unsupported Devices

- **macOS any version** (Apple supports OpenGL up to 4.1 only)
- **Intel Integrated Graphics** (Pre-10th Gen, incomplete driver support)
- **NVIDIA GeForce 600/700 Series** (Some models lack GL version)
- **AMD Radeon HD 7000 Series & older** (Poor Compute Shader support)

---

## 📦 Installation

### Prerequisites

1. Install [Fabric Loader](https://fabricmc.net/use/)
2. Install [Sodium](https://modrinth.com/mod/sodium)
3. (Optional) Install [Lithium](https://modrinth.com/mod/lithium) for game logic optimization

### Steps

1. Download the latest `potassium-{version}.jar`
2. Place the file into `.minecraft/mods/` directory
3. Launch the game and check logs for successful initialization

### Verify Installation

After launching, check logs for the following output:

```
[main/INFO]: Potassium is initializing...
[main/INFO]: ✓ Sodium detected
[main/INFO]: OpenGL Version: 4.6
[main/INFO]: Indirect Drawing: true
[main/INFO]: Compute Shader: true
[main/INFO]: ✓ Potassium initialized successfully!
```

---

## ⚙️ Configuration

Config file located at `config/potassium.json`:

```json
{
  "rendering": {
    "enable_indirect_draw": true,
    "enable_gpu_culling": true,
    "enable_persistent_mapping": true,
    "debug_overlay": false
  },
  "performance": {
    "max_indirect_draw_count": 65536,
    "buffer_size_mb": 256
  }
}
```

### Config Options

| Option | Default | Description |
| :--- | :--- | :--- |
| `enable_indirect_draw` | true | Enable Indirect Drawing (Core Feature) |
| `enable_gpu_culling` | true | Enable GPU-side culling (Requires Compute Shader) |
| `enable_persistent_mapping` | true | Enable Persistent Mapping Buffers |
| `debug_overlay` | false | Show rendering debug info (F3 Screen) |
| `max_indirect_draw_count` | 65536 | Max indirect draw command count |
| `buffer_size_mb` | 256 | Command buffer size (MB) |

---

## 🏗️ Technical Architecture

### Rendering Pipeline Comparison

```
┌─────────────────────────────────────────────────────────┐
│                    Sodium Native Pipeline                │
├─────────────────────────────────────────────────────────┤
│  CPU Frustum Culling → CPU Meshing → glDrawArrays (Multi)│
│                      ↑                                  │
│              CPU Bottleneck: Too Many Draw Calls         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                   Potassium Pipeline                     │
├─────────────────────────────────────────────────────────┤
│  GPU Compute Shader Culling → Write Indirect Buffer     │
│                      ↓                                  │
│  glMultiDrawArraysIndirect (Single Call)                │
│                      ↑                                  │
│              GPU Driven: Minimal CPU Overhead            │
└─────────────────────────────────────────────────────────┘
```

### Core Modules

| Module | Description |
| :--- | :--- |
| `GLCapabilities` | OpenGL capability detection and version validation |
| `IndirectCommandBuffer` | Indirect draw command buffer management |
| `GpuCullingPipeline` | GPU-side culling compute pipeline |
| `PersistentBuffer` | Persistent mapping buffer wrapper |
| `SodiumBridge` | Sodium rendering backend adaptation layer |

---

## 📅 Roadmap

### Phase 1 - Infrastructure (Current)
- [x] Project initialization and build configuration
- [x] OpenGL capability detection system
- [x] Hard fail startup check
- [ ] IndirectCommandBuffer implementation
- [ ] CPU-side indirect drawing demo

### Phase 2 - Core Features
- [ ] Sodium rendering backend integration
- [ ] Persistent mapping buffers
- [ ] Chunk indirect drawing integration
- [ ] Performance benchmarking tools

### Phase 3 - GPU Driven
- [ ] Compute Shader culling pipeline
- [ ] GPU-based LOD system
- [ ] Chained indirect drawing
- [ ] Multi-GPU support experiment

### Phase 4 - Optimization & Polish
- [ ] Bindless Texture support
- [ ] Shader compatibility layer
- [ ] Performance analysis overlay
- [ ] Documentation and examples

---

## ❓ FAQ

### Q: Why does it say "OpenGL Version Insufficient" on startup?
**A:** Your GPU or driver does not support OpenGL 4.5+. Please try:
1. Update GPU driver to the latest version
2. Confirm GPU hardware supports OpenGL 4.5
3. If using macOS, this mod cannot run (System limitation)

### Q: Why is Sodium required?
**A:** Potassium is an **extension** of Sodium, not a replacement. We reuse Sodium's meshing and memory management, only replacing the draw submission layer.

### Q: Is it compatible with Iris Shaders?
**A:** Currently experimental. Some shaders may not work correctly. We are developing a compatibility layer.

### Q: What if performance doesn't improve?
**A:** Please check:
1. Logs confirm indirect drawing is enabled
2. Use F3 to check if Draw Calls are reduced
3. Try adjusting buffer size in config
4. Submit a performance report on GitHub

### Q: Will Forge/NeoForge be supported?
**A:** Currently Fabric only. Due to architectural differences, Forge version requires redesign, no plans yet.

---

## 🤝 Contributing

Contributions are welcome!

### Dev Environment Setup
```bash
git clone https://github.com/yourusername/potassium.git
cd potassium
./gradlew setupDecompileWorkspace
./gradlew genSources
```

### Commit Convention
- Feature: `feat: description`
- Bug Fix: `fix: description`
- Performance: `perf: description`
- Docs: `docs: description`

### Testing Requirements
- All PRs must pass `./gradlew build`
- Performance changes must include benchmark data
- New features must update documentation

---

## 📄 License

This project is licensed under the **GNU Lesser General Public License v3.0**.

- ✅ Personal use, modification, distribution allowed
- ✅ Allowed as a dependency for other mods
- ⚠️ Modified source code must be公开 (open sourced)
- ⚠️ Original author attribution must be preserved

See [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgements

- [Sodium](https://github.com/CaffeineMC/sodium-fabric) - Base rendering engine
- [Voxy](https://github.com/voxy-mod/voxy) - OpenGL advanced features reference
- [LWJGL](https://www.lwjgl.org/) - Java OpenGL bindings
- [FabricMC](https://fabricmc.net/) - Mod loader

---

<div align="center">

**Made with ⚡ by MuXue1230**

[⭐ Star](../../stargazers) · [🍴 Fork](../../forks) · [🐛 Issues](../../issues)

</div>