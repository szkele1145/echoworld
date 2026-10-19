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
 * WorldReplay Fabric 1.20.1 网络通道与包处理。
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
        // Fabric 1.20.1 registerGlobalReceiver 回调签名：(handler, player, buf, responseSender)
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_RECORD_CONTROL,
                (server, player, handler, buf, responseSender) -> {
                    boolean start = buf.readBoolean();
                    int count = buf.readVarInt();
                    List<UUID> players = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        players.add(buf.readUuid());
                    }
                    server.execute(() -> {
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
                    server.execute(() -> RecordControlHandler.openRecordLibrary(player));
                });

        ServerPlayNetworking.registerGlobalReceiver(CHANNEL_REQUEST_RECORDING,
                (server, player, handler, buf, responseSender) -> {
                    String fileName = buf.readString(32767);
                    int action = buf.readVarInt();
                    server.execute(() -> {
                        if (action == 0) {
                            Path dir = RecordControlHandler.recordingsDir();
                            java.nio.file.Path f = dir.resolve(RecordControlHandler.hasWrpSuffix(fileName)
                                    ? fileName : fileName + ".wrp");
                            if (java.nio.file.Files.isRegularFile(f)) {
                                try {
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
