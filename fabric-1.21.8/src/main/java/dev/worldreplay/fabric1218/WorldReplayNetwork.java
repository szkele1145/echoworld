package dev.worldreplay.fabric1218;

import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadOpenRecordLibrary;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordControl;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordList;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordingData;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRequestRecordList;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRequestRecording;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRequestRename;
import dev.worldreplay.fabric1218.WorldReplayPayloads.RecordListEntry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * WorldReplay Fabric 1.21.8 网络通道与包处理。
 *
 * <p>1.21.x 适配：Fabric API 的网络层重构成
 * {@link net.minecraft.network.packet.CustomPayload} + {@code PacketCodec} 模型。
 * 每个通道先在 {@link PayloadTypeRegistry} 注册类型，再挂
 * {@link ServerPlayNetworking#registerGlobalReceiver} 处理器。
 * 字节布局与 1.20.x 保持一致（见 {@link WorldReplayPayloads}）。</p>
 */
public final class WorldReplayNetwork {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldReplayNetwork.class);

    /** 最大分片大小（24 KiB）。 */
    public static final int MAX_CHUNK = 24 * 1024;

    private WorldReplayNetwork() {}

    /** 注册所有 payload 类型与服务端网络接收器。 */
    public static void register() {
        // ---- payload 类型注册（1.21.x 新增，必须在收包前完成）----
        PayloadTypeRegistry.playC2S().register(PayloadRecordControl.ID, PayloadRecordControl.CODEC);
        PayloadTypeRegistry.playC2S().register(PayloadRequestRecordList.ID, PayloadRequestRecordList.CODEC);
        PayloadTypeRegistry.playC2S().register(PayloadRequestRecording.ID, PayloadRequestRecording.CODEC);
        PayloadTypeRegistry.playC2S().register(PayloadRequestRename.ID, PayloadRequestRename.CODEC);
        PayloadTypeRegistry.playS2C().register(PayloadRecordList.ID, PayloadRecordList.CODEC);
        PayloadTypeRegistry.playS2C().register(PayloadRecordingData.ID, PayloadRecordingData.CODEC);
        PayloadTypeRegistry.playS2C().register(PayloadOpenRecordLibrary.ID, PayloadOpenRecordLibrary.CODEC);

        // ---- 服务端接收器（客户端→服务端）----
        ServerPlayNetworking.registerGlobalReceiver(PayloadRecordControl.ID,
                (payload, context) -> {
                    var server = context.server();
                    var player = context.player();
                    server.execute(() -> {
                        // OP 限定：录制控制能影响**任意玩家**（开始/停止别人的录制），
                        // 普通玩家不应有权限。与 NeoForge 侧保持一致。
                        if (!player.hasPermissionLevel(2)) return;
                        if (payload.start()) {
                            for (UUID id : payload.players()) {
                                WorldReplayRecorder.startFor(server, id);
                            }
                        } else {
                            for (UUID id : payload.players()) {
                                WorldReplayRecorder.stopFor(server, id);
                            }
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(PayloadRequestRecordList.ID,
                (payload, context) -> {
                    var server = context.server();
                    var player = context.player();
                    // OP 限定：列表包含**全部**录制文件（所有玩家的行为记录）
                    server.execute(() -> {
                        if (!player.hasPermissionLevel(2)) return;
                        RecordControlHandler.openRecordLibrary(player);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(PayloadRequestRecording.ID,
                (payload, context) -> {
                    var server = context.server();
                    var player = context.player();
                    String fileName = payload.fileName();
                    int action = payload.action();
                    server.execute(() -> {
                        // OP 限定：回放=下载文件数据，删除=删文件——两种都必须是管理员操作
                        if (!player.hasPermissionLevel(2)) return;
                        // 防目录穿越：包可以手工构造，不能只靠客户端。
                        // 旧实现直接 dir.resolve(fileName)——"../../../../secret"
                        // 会读出 recordings 目录之外的文件并回传给客户端。
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
                                    // 与客户端上限对齐：超大录制先挡住，
                                    // 别 readAllBytes 进内存再被客户端丢弃
                                    long size = java.nio.file.Files.size(f);
                                    if (size > RecordControlHandler.MAX_SEND_BYTES) {
                                        RecordControlHandler.notifyFailure(player,
                                                "录制文件过大（上限 480 MiB）");
                                        return;
                                    }
                                    byte[] data = java.nio.file.Files.readAllBytes(f);
                                    sendRecording(player, f.getFileName().toString(), data);
                                } catch (Exception e) {
                                    WorldReplayFabric.LOGGER.warn("[WorldReplay] Failed to load recording {}", fileName, e);
                                }
                            }
                        } else if (action == 1) {
                            RecordControlHandler.deleteRecording(player, fileName);
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(PayloadRequestRename.ID,
                (payload, context) -> {
                    var server = context.server();
                    var player = context.player();
                    String oldName = payload.oldName();
                    String newName = payload.newName();
                    server.execute(() -> {
                        // OP 限定：重命名会动别人的录制文件
                        if (!player.hasPermissionLevel(2)) return;
                        if (!WorldReplayConfig.RENAME_ENABLED) {
                            player.sendMessage(net.minecraft.text.Text.literal(
                                    "[WorldReplay] 重命名功能已禁用"), false);
                            return;
                        }
                        RecordControlHandler.renameRecording(player, oldName, newName);
                    });
                });

        LOGGER.info("[WorldReplay] Network channels registered");
    }

    /** 把录制文件数据分片发送给指定玩家，返回发送的分片数。 */
    public static int sendRecording(ServerPlayerEntity target, String fileName, byte[] data) {
        int chunkIndex = 0;
        int chunks = 0;
        for (int off = 0; off < data.length; ) {
            int len = Math.min(MAX_CHUNK, data.length - off);
            byte[] slice = java.util.Arrays.copyOfRange(data, off, off + len);
            ServerPlayNetworking.send(target, new PayloadRecordingData(
                    fileName, chunkIndex, off + len >= data.length, slice));
            off += len;
            chunkIndex++;
            chunks++;
        }
        return chunks;
    }

    /** 把录制文件列表发送给指定玩家。 */
    public static void sendRecordList(ServerPlayerEntity target, List<RecordListPackets.RecordListEntry> entries) {
        List<RecordListEntry> payloadEntries = entries.stream()
                .map(e -> new RecordListEntry(e.fileName, e.size, e.lastModified, e.events))
                .toList();
        ServerPlayNetworking.send(target, new PayloadRecordList(payloadEntries));
    }

    /** 发送 OpenRecordLibrary 信号给客户端，指示其打开录制库 GUI。 */
    public static void sendOpenRecordLibrary(ServerPlayerEntity target) {
        ServerPlayNetworking.send(target, new PayloadOpenRecordLibrary());
    }
}
