# WorldReplay

**全量行为录制与回放**——在 Minecraft 世界里重现玩家做过的一切：方块增减、实体移动、容器操作。

> 独立于 GlowTrack（玩家轨迹追踪）的新项目。GlowTrack 回答"他去过哪"，WorldReplay 回答"他干了什么"。

## 架构

```
worldreplay/
├── core/              # 事件格式 + 回放引擎（纯逻辑，无 MC 依赖，带零依赖自检）
├── nf-1.21.1/         # NeoForge 1.21.1 平台适配
├── fabric-1.20.1/     # Fabric 1.20.1
└── fabric-1.19.4/     # Fabric 1.19.4
```

## 支持矩阵

| 平台 | 版本 | 状态 |
|------|------|------|
| NeoForge | 1.21.1 | ✅ [0.2.0](https://modrinth.com/mod/worldreplay) |
| Fabric | 1.20.1 | ✅ 0.2.0 |
| Fabric | 1.19.4 | ✅ 0.2.0 |
| Fabric | 1.19.2 | 📋 规划 |

## 功能

- **自动录制**：玩家进服自动开始录制，退出自动落盘（`.wrp` 格式）
- **事件捕获**：方块破坏 / 右键交互，含方块坐标（非玩家坐标）
- **录制库**：列表滚动、选中回放、重命名、删除（二次确认）
- **回放**：世界内线框重现，0.25x–64x 滚轮倍速，暂停（P），进度条 HUD
- **自动清理**：超 N 天的录制自动删除（配置项 `autoCleanupDays`，0 = 关闭）
- **`/wr` 命令族**：服务端管理录制

## 构建

每平台独立构建（Gradle 8.11+，JDK 21 编译 core 产出 17 字节码）：

```powershell
# core（含自检：gradle selfTest / check）
cd core && gradle check

# NeoForge 1.21.1
cd nf-1.21.1 && gradle build

# Fabric 1.20.1 / 1.19.4
cd fabric-1.20.1 && gradle build
```

产物在各模块 `build/libs/worldreplay-<版本>-<MC>-<loader>.jar`。

## 设计原则

1. **录制即事实**：事件捕获走 Minecraft 原生方块/实体系统，第三方 mod（MTR 等）的自定义方块自动被捕获，无需特殊适配
2. **格式即标准**：录制文件格式版本化、可扩展、向后兼容（magic `0xE7C0`，当前 v1）
3. **回放即世界**：不是看录像，是在游戏世界里重现
4. **模块化**：core 零 MC 依赖，平台适配层薄

## License

All rights reserved.
