# WorldReplay 多平台移植说明

本仓库为同一个模组维护 9 个平台。各模块**独立持有完整源码**，不共享 core 的
Minecraft 侧代码（core 是纯 Java 的事件格式与编解码，可以共享）。

## 平台与产物

| 模块 | MC | 加载器 | 映射 | 产物 |
|---|---|---|---|---|
| `fabric-1.18.2` | 1.18.2 | Fabric | Yarn | `worldreplay-<ver>-1.18.2-fabric.jar` |
| `fabric-1.19.2` | 1.19.2 | Fabric | Yarn | `worldreplay-<ver>-1.19.2-fabric.jar` |
| `fabric-1.19.4` | 1.19.4 | Fabric | Yarn | `worldreplay-<ver>-1.19.4-fabric.jar` |
| `fabric-1.20.1` | 1.20.1 | Fabric | Yarn | `worldreplay-<ver>-1.20.1-fabric.jar` |
| `fabric-1.21.8` | 1.21.8 | Fabric | Yarn | `worldreplay-<ver>-1.21.8-fabric.jar` |
| `fabric-1.21.11` | 1.21.11 | Fabric | Yarn | `worldreplay-<ver>-1.21.11-fabric.jar` |
| `forge-1.19.4` | 1.19.4 | Forge 45.4.5 | official | `worldreplay-<ver>-1.19.4-forge.jar` |
| `forge-1.20.1` | 1.20.1 | Forge 47.2.0 | official | `worldreplay-<ver>-1.20.1-forge.jar` |
| `nf-1.21.1` | 1.21.1 | NeoForge 21.1 | official | `worldreplay-<ver>-1.21.1-neoforge.jar` |

Fabric 模块用 Yarn 映射（`World` / `PlayerEntity` / `BlockItem`），Forge / NeoForge
模块用 Mojang 官方映射（`Level` / `Player`）。**两边代码不能直接互相复制。**

## 网络层

| 平台 | 机制 |
|---|---|
| 1.21.8 / 1.21.11 | `CustomPacketPayload` + `StreamCodec` |
| 1.19.4 | Fabric `FabricPacket` + `PacketType` |
| 1.19.2 / 1.18.2 | 老通道模型（`ResourceLocation` + `FriendlyByteBuf`） |
| Forge 1.19.4 / 1.20.1 | `SimpleChannel` |
| NeoForge 1.21.1 | `RegisterPayloadHandlersEvent` |

各平台的 `WorldReplayNetwork` 负责注册与发送，发包一律包 try/catch——对端没装模组
时通道未协商，不兜底就会在 GUI 点击回调里抛异常。

## block_place 回放钩子

六个 Fabric 平台都 mixin `BlockItem#postPlacement`。注入点的 Yarn 签名在
1.18.2 / 1.19.2 / 1.19.4 / 1.20.1 / 1.21.8 / 1.21.11 **完全一致**：

```
protected boolean postPlacement(BlockPos, World, PlayerEntity, ItemStack, BlockState)
```

（逐版本用 javap 对着各版 Yarn jar 核实过，不是猜的。）

注入用 `@At("RETURN")` + `CallbackInfoReturnable<Boolean>`，`mixins.json` 是
`required: true` + `defaultRequire: 1`——注入失败会**响亮地报错**而不是静默失效，
这比静默好。

## GUI

| 平台 | 绘制上下文 | 备注 |
|---|---|---|
| 1.21.x / Forge 1.20.1 | `GuiGraphics` | 1.20 引入 |
| 1.19.x / 1.18.2 / Forge 1.19.4 | `PoseStack` | `GuiComponent.fill` + `Font.draw` |
| 1.18.2 额外 | — | `new TextComponent(...)`（无 `Component.literal` 静态工厂） |

`render` 的签名也跟着变：1.19.4 是 `render(MatrixStack, int, int, float)`，
1.20.1 是 `render(DrawContext, int, int, float)`。**跨版本同步 UI 代码前必须逐行
diff**，整份覆盖会导致编译失败（踩过）。

## 已经踩过的坑（发布前对照）

1. **`mods.toml` 的硬编码值不会跟构建参数走**
   Forge 1.19.4 的 `loaderVersion` 被写成 `[47,)`（从 1.20.1 复制），实际是 45，
   模组在加载阶段被拒：`needs language provider javafml:47 or above to load`。
   所有 `*_version_range` 都应该用 `${...}` 占位符，由 `processResources` 注入。

2. **Fabric entrypoint 构造器必须可访问**
   Loader 用反射实例化入口类，`private XxxFabric() {}` 会抛
   `IllegalAccessException`，整个模组加载失败。入口类不要套工具类的写法。
   （本仓库的入口类没有显式构造器，靠默认 public 构造器，是对的。）

3. **Forge 一个 `@EventBusSubscriber` 类只能属于一个总线**
   NeoForge 21.x 会按订阅方法的参数类型自动分流，Forge 不会。往 Forge 搬
   NeoForge 代码时，逐个核对类里每个 `@SubscribeEvent` 的参数是否同属一条总线。
   本仓库的 Forge 模块是手动 `MinecraftForge.EVENT_BUS.addListener`，避开了这个问题；
   但 NeoForge 模块的 `RecordControlHandler`（无 bus 注解 + `RegisterPayloadHandlersEvent`）
   在 Forge 上会出问题，别照抄。

4. **jar 里不要留 `neoforge.mods.toml`**（母版残留，占位符未展开）。

## 复验方式

改完网络 / GUI / 事件层后，别只跑 `build`。启动一次服务端：

```
cd <模块目录>
# run/eula.txt 写 eula=true
# run/server.properties 换个不冲突的端口（如 25581）
gradle runServer
```

看到 `Done (x.xxxs)!` 加上 `[WorldReplay] Network channels registered` 才算通过。
这一步能抓到编译期完全看不见的问题——本仓库的 `loaderVersion` 就是这么发现的。

`core` 的自检不需要游戏运行时：

```
javac -encoding UTF-8 -d <out> core/src/main/java/**/*.java core/src/test/java/**/*.java
java -cp <out> dev.worldreplay.core.event.CoreSelfTest
```

当前 65 条断言。
