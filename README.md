# Potassium

Potassium 是一个独立的 Minecraft GPU Driven 渲染引擎项目，不再依赖 Sodium。

## 当前状态

- 当前版本：`v0.0.1`
- 当前阶段：`Phase 0`
- 目标：先完成可编译、可启动、可硬探测 OpenGL 4.5/4.6 的独立引擎骨架，再逐步接入显存常驻世界数据、GPU 网格生成和间接绘制。

## Phase 0 范围

- OpenGL 4.6 优先，4.5 回退
- OpenGL 能力探测与硬失败
- 持久化缓冲区封装
- ShaderProgram / ComputeShader 装载与编译
- 独立渲染管线骨架
- 世界数据、区块管理、变更追踪基础结构

## 已移除内容

- 所有 `Sodium` 依赖
- 所有 `Sodium` mixin / compat / shader / config 代码
- 旧的桥接式间接绘制实现
- 不必要的 vendored `org.joml.Matrix4f`

## 目录

```text
src/main/java/com/potassium/
  core/
  gl/
  world/
  render/
  entity/
  ui/
  mixin/

src/main/resources/
  fabric.mod.json
  potassium.mixins.json
  assets/potassium/lang/
  shaders/
```

## 构建

```bash
./gradlew build
```

## 后续阶段

1. Phase 1：世界数据常驻显存与增量同步
2. Phase 2：GPU 网格生成
3. Phase 3：间接绘制与 GPU 剔除
4. Phase 4：高级特性与兼容层
