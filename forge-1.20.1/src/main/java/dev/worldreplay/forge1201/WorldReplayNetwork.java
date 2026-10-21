package dev.worldreplay.forge1201;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * WorldReplay Forge 1.20.1 网络通道与包处理。
 *
 * <p>使用 Forge 的 {@link SimpleChannel}：每个自定义包一个序号，
 * 客户端与服务端各注册一次。字节布局与 Fabric / NeoForge 版一致。</p>
 */
public final class WorldReplayNetwork {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldReplayNetwork.class);

    /** 通道协议版本：与 Fabric / NeoForge 侧的包序号保持一致。 */
    public static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(WorldReplayForge.MOD_ID, "main"))
            .networkProtocolVersion(() -> PROTOCOL_VERSION)
            .clientAcceptedVersions(WorldReplayNetwork::isAccepted)
            .serverAcceptedVersions(WorldReplayNetwork::isAccepted)
            .simpleChannel();

    /** 包序号计数器（register() 内自增，顺序即协议顺序）。 */
    private static int id = 0;

    /** 最大分片大小（24 KiB）。 */
    public static final int MAX_CHUNK = 24 * 1024;

    private WorldReplayNetwork() {}

    private static boolean isAccepted(String version) {
        return PROTOCOL_VERSION.equals(version);
    }

    /** 注册所有网络消息。 */
    public static void register() {
        // ---- 服务端接收器（客户端→服务端）----
        CHANNEL.registerMessage(id++, RecordListPackets.RecordControl.class,
                RecordListPackets::encodeRecordControl, RecordListPackets::decodeRecordControl,
                WorldReplayNetwork::handleRecordControl);

        CHANNEL.registerMessage(id++, RecordListPackets.RequestRecordList.class,
                (msg, buf) -> { }, (buf) -> new RecordListPackets.RequestRecordList(),
                WorldReplayNetwork::handleRequestRecordList);

        CHANNEL.registerMessage(id++, RecordListPackets.RequestRecording.class,
                RecordListPackets::encodeRequestRecording, RecordListPackets::decodeRequestRecording,
                WorldReplayNetwork::handleRequestRecording);

        CHANNEL.registerMessage(id++, RecordListPackets.RequestRename.class,
                RecordListPackets::encodeRequestRename, RecordListPackets::decodeRequestRename,
                WorldReplayNetwork::handleRequestRename);

        // ---- 客户端接收器（服务端→客户端）----
        CHANNEL.registerMessage(id++, RecordListPackets.RecordList.class,
                RecordListPackets::encodeRecordList, RecordListPackets::decodeRecordList,
                WorldReplayNetwork::handleRecordList);

        CHANNEL.registerMessage(id++, RecordListPackets.RecordingData.class,
                RecordListPackets::encodeRecordingData, RecordListPackets::decodeRecordingData,
                WorldReplayNetwork::handleRecordingData);

        CHANNEL.registerMessage(id++, RecordListPackets.OpenRecordLibrary.class,
                (msg, buf) -> { }, (buf) -> new RecordListPackets.OpenRecordLibrary(),
                WorldReplayNetwork::handleOpenRecordLibrary);

        LOGGER.info("[WorldReplay] Network channels registered");
    }

    // ==================== 服务端处理器 ====================

    private static void handleRecordControl(RecordListPackets.RecordControl ctrl,
                                            Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            var server = player.server;
            // OP 限定：录制控制能影响**任意玩家**（开始/停止别人的录制），
            // 普通玩家不应有权限。与 NeoForge / Fabric 侧保持一致。
            if (!player.hasPermissions(2)) return;
            if (ctrl.start) {
                for (UUID uid : ctrl.players) {
                    WorldReplayRecorder.startFor(server, uid);
                }
            } else {
                for (UUID uid : ctrl.players) {
                    WorldReplayRecorder.stopFor(server, uid);
                }
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleRequestRecordList(RecordListPackets.RequestRecordList msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            // OP 限定：列表包含**全部**录制文件（所有玩家的行为记录）
            if (!player.hasPermissions(2)) return;
            RecordControlHandler.openRecordLibrary(player);
        });
        context.setPacketHandled(true);
    }

    private static void handleRequestRecording(RecordListPackets.RequestRecording req,
                                               Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            String fileName = req.fileName;
            int action = req.action;
            // OP 限定：回放=下载文件数据，删除=删文件——两种都必须是管理员操作
            if (!player.hasPermissions(2)) return;
            // 防目录穿越：包可以手工构造，不能只靠客户端。
            if (!RecordControlHandler.isSafeFileName(fileName)) {
                RecordControlHandler.notifyFailure(player, "非法文件名");
                return;
            }
            Path dir = RecordControlHandler.recordingsDir();
            Path f = dir.resolve(RecordControlHandler.hasWrpSuffix(fileName)
                    ? fileName : fileName + ".wrp");
            if (!f.normalize().getParent().equals(dir.normalize())) {
                RecordControlHandler.notifyFailure(player, "非法路径");
                return;
            }
            if (action == 0) {
                if (java.nio.file.Files.isRegularFile(f)) {
                    try {
                        // 与客户端上限对齐：超大录制先挡住
                        long size = java.nio.file.Files.size(f);
                        if (size > RecordControlHandler.MAX_SEND_BYTES) {
                            RecordControlHandler.notifyFailure(player, "录制文件过大（上限 480 MiB）");
                            return;
                        }
                        byte[] data = java.nio.file.Files.readAllBytes(f);
                        sendRecording(player, f.getFileName().toString(), data);
                    } catch (Exception e) {
                        WorldReplayForge.LOGGER.warn("[WorldReplay] Failed to load recording {}", fileName, e);
                    }
                }
            } else if (action == 1) {
                RecordControlHandler.deleteRecording(player, fileName);
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleRequestRename(RecordListPackets.RequestRename req,
                                            Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            // OP 限定：重命名会动别人的录制文件
            if (!player.hasPermissions(2)) return;
            if (!WorldReplayConfig.RENAME_ENABLED) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("[WorldReplay] 重命名功能已禁用"), false);
                return;
            }
            RecordControlHandler.renameRecording(player, req.oldName, req.newName);
        });
        context.setPacketHandled(true);
    }

    // ==================== 客户端处理器 ====================

    private static void handleRecordList(RecordListPackets.RecordList list,
                                         Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            // 客户端逻辑必须隔离在 DistExecutor 里：服务端运行时会因为
            // 加载不到 net.minecraft.client.* 类而崩溃。
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.worldreplay.forge1201.client.ClientPayloadHandlers.applyRecordList(list));
        });
        context.setPacketHandled(true);
    }

    private static void handleRecordingData(RecordListPackets.RecordingData data,
                                            Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.worldreplay.forge1201.client.ClientPayloadHandlers.applyRecordingData(data));
        });
        context.setPacketHandled(true);
    }

    private static void handleOpenRecordLibrary(RecordListPackets.OpenRecordLibrary msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> dev.worldreplay.forge1201.client.ClientPayloadHandlers.openRecordLibrary());
        });
        context.setPacketHandled(true);
    }

    // ==================== 发送 ====================

    /** 把录制文件数据分片发送给指定玩家，返回发送的分片数。 */
    public static int sendRecording(ServerPlayer target, String fileName, byte[] data) {
        int chunkIndex = 0;
        int chunks = 0;
        for (int off = 0; off < data.length; ) {
            int len = Math.min(MAX_CHUNK, data.length - off);
            byte[] slice = java.util.Arrays.copyOfRange(data, off, off + len);
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> target),
                    new RecordListPackets.RecordingData(
                            fileName, chunkIndex, off + len >= data.length, slice));
            off += len;
            chunkIndex++;
            chunks++;
        }
        return chunks;
    }

    /** 把录制文件列表发送给指定玩家。 */
    public static void sendRecordList(ServerPlayer target, List<RecordListPackets.RecordListEntry> entries) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> target),
                new RecordListPackets.RecordList(entries));
    }

    /** 发送 OpenRecordLibrary 信号给客户端，指示其打开录制库 GUI。 */
    public static void sendOpenRecordLibrary(ServerPlayer target) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> target),
                new RecordListPackets.OpenRecordLibrary());
    }
}
