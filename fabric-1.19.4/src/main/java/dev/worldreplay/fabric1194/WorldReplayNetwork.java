package dev.worldreplay.fabric1194;

import dev.worldreplay.core.event.EventDecoder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WorldReplay Fabric 1.19.4 网络通道与包处理。
 *
 * <p>注册所有自定义包通道，处理客户端↔服务端的录制控制、列表查询、回放分片下发。
 * 客户端接收器由 {@link dev.worldreplay.fabric1194.client.ClientPayloadHandlers} 在客户端入口中注册。</p>
 */
public final class WorldReplayNetwork {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WorldReplayNetwork.class);

    public static final Identifier CHANNEL_RECORD_CONTROL = new Identifier("worldreplay", "record_control");
    public static final Identifier CHANNEL_RECORD_LIST = new Identifier("worldreplay", "record_list");
    public static final Identifier CHANNEL_RECORDING_DATA = new Identifier("worldreplay", "recording_data");
    public static final Identifier CHANNEL_REQUEST_RECORD_LIST = new Identifier("worldreplay", "request_record_list");
    public static final Identifier CHANNEL_REQUEST_RECORDING = new Identifier("worldreplay", "request_recording");
    public static final Identifier CHANNEL_REQUEST_RENAME = new Identifier("worldreplay", "request_rename");
    public static final Identifier CHANNEL_OPEN_RECORD_LIBRARY = new Identifier("worldreplay", "open_record_library");

    /** 最大分片大小（24 KiB）。 */
    public static final int MAX_CHUNK = 24 * 1024;

    private WorldReplayNetwork() {}

    /** 注册所有服务端网络接收器。 */
    public static void register() {
        // ---- 服务端接收器（客户端→服务端）----
        // Fabric 1.19.4 registerGlobalReceiver 回调签名：(handler, player, buf, responseSender)
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_RECORD_CONTROL,
                (server, player, handler, buf, responseSender) -> {
                    boolean start = buf.readBoolean();
                    int count = buf.readVarInt();
                    List<UUID> players = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        players.add(buf.readUuid());
                    }
                    server.execute(() -> {
                        // OP 限定：录制控制能影响**任意玩家**（开始/停止别人的录制），
                        // 普通玩家不应有权限。与 NeoForge 侧保持一致。
                        if (!player.hasPermissionLevel(2)) return;
                        if (start) {
                            for (UUID id : players) {
                                WorldReplayRecorder.startFor(server, id);
                            }
                        } else {
                            for (UUID id : players) {
                                WorldReplayRecorder.stopFor(server, id);
                            }
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_REQUEST_RECORD_LIST,
                (server, player, handler, buf, responseSender) -> {
                    // OP 限定：列表包含**全部**录制文件（所有玩家的行为记录）
                    server.execute(() -> {
                        if (!player.hasPermissionLevel(2)) return;
                        RecordControlHandler.openRecordLibrary(player);
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_REQUEST_RECORDING,
                (server, player, handler, buf, responseSender) -> {
                    String fileName = buf.readString(32767);
                    int action = buf.readVarInt();
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

        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_REQUEST_RENAME,
                (server, player, handler, buf, responseSender) -> {
                    String oldName = buf.readString(32767);
                    String newName = buf.readString(32767);
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
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(fileName);
            buf.writeVarInt(chunkIndex++);
            buf.writeBoolean(off + len >= data.length);
            buf.writeByteArray(java.util.Arrays.copyOfRange(data, off, off + len));
            ServerPlayNetworking.send(target, CHANNEL_RECORDING_DATA, buf);
            off += len;
            chunks++;
        }
        return chunks;
    }

    /** 把录制文件列表发送给指定玩家。 */
    public static void sendRecordList(ServerPlayerEntity target, List<RecordListPackets.RecordListEntry> entries) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(entries.size());
        for (RecordListPackets.RecordListEntry e : entries) {
            buf.writeString(e.fileName);
            buf.writeVarLong(e.size);
            buf.writeVarLong(e.lastModified);
        }
        ServerPlayNetworking.send(target, CHANNEL_RECORD_LIST, buf);
    }

    /** 发送 OpenRecordLibrary 信号给客户端，指示其打开录制库 GUI。 */
    public static void sendOpenRecordLibrary(ServerPlayerEntity target) {
        PacketByteBuf buf = PacketByteBufs.create();
        ServerPlayNetworking.send(target, CHANNEL_OPEN_RECORD_LIBRARY, buf);
    }
}
