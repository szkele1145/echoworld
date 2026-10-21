package dev.worldreplay.fabric1218;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WorldReplay Fabric 1.21.8 自定义 payload 定义。
 *
 * <p>1.21.x 的 Fabric API 网络层重构成 {@link CustomPayload} + {@link PacketCodec}
 * 模型（1.20.x 是"裸 PacketByteBuf + Identifier"）。每个通道对应一个 payload record，
 * 字节布局与 1.20.x 保持一致。</p>
 *
 * <p>codec 用 {@link PacketCodec#ofStatic} 手写编解码（复用 PacketByteBuf 的
 * 读写方法），而不是用 PacketCodecs 组合子 —— 这样和旧版的字节布局完全一致，
 * 且分片/大文件逻辑不用改。</p>
 */
public final class WorldReplayPayloads {

    private WorldReplayPayloads() {}

    private static Identifier id(String path) {
        return Identifier.of(WorldReplayFabric.MOD_ID, path);
    }

    // ==================== C2S：开始/停止录制 ====================

    /** 客户端→服务端：批量开始或停止录制。 */
    public record PayloadRecordControl(boolean start, List<UUID> players) implements CustomPayload {
        public static final CustomPayload.Id<PayloadRecordControl> ID = new CustomPayload.Id<>(id("record_control"));
        public static final PacketCodec<PacketByteBuf, PayloadRecordControl> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> {
                            buf.writeBoolean(value.start());
                            buf.writeVarInt(value.players().size());
                            for (UUID u : value.players()) {
                                buf.writeUuid(u);
                            }
                        },
                        buf -> {
                            boolean start = buf.readBoolean();
                            int count = buf.readVarInt();
                            List<UUID> players = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
                            for (int i = 0; i < count; i++) {
                                players.add(buf.readUuid());
                            }
                            return new PayloadRecordControl(start, players);
                        });

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ==================== C2S：请求录制文件列表 ====================

    /** 客户端→服务端：请求录制文件列表（无正文）。 */
    public record PayloadRequestRecordList() implements CustomPayload {
        public static final CustomPayload.Id<PayloadRequestRecordList> ID = new CustomPayload.Id<>(id("request_record_list"));
        public static final PacketCodec<PacketByteBuf, PayloadRequestRecordList> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> { },
                        buf -> new PayloadRequestRecordList());

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ==================== C2S：请求回放或删除 ====================

    /** 客户端→服务端：请求回放（action=0）或删除（action=1）指定录制。 */
    public record PayloadRequestRecording(String fileName, int action) implements CustomPayload {
        public static final CustomPayload.Id<PayloadRequestRecording> ID = new CustomPayload.Id<>(id("request_recording"));
        public static final PacketCodec<PacketByteBuf, PayloadRequestRecording> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> {
                            buf.writeString(value.fileName());
                            buf.writeVarInt(value.action());
                        },
                        buf -> new PayloadRequestRecording(buf.readString(32767), buf.readVarInt()));

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ==================== C2S：请求重命名 ====================

    /** 客户端→服务端：请求重命名录制文件。 */
    public record PayloadRequestRename(String oldName, String newName) implements CustomPayload {
        public static final CustomPayload.Id<PayloadRequestRename> ID = new CustomPayload.Id<>(id("request_rename"));
        public static final PacketCodec<PacketByteBuf, PayloadRequestRename> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> {
                            buf.writeString(value.oldName());
                            buf.writeString(value.newName());
                        },
                        buf -> new PayloadRequestRename(buf.readString(32767), buf.readString(32767)));

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ==================== S2C：录制文件列表 ====================

    /** 服务端→客户端：录制文件列表条目。 */
    public record RecordListEntry(String fileName, long size, long lastModified, int events) {
    }

    /** 服务端→客户端：录制文件列表。 */
    public record PayloadRecordList(List<RecordListEntry> entries) implements CustomPayload {
        public static final CustomPayload.Id<PayloadRecordList> ID = new CustomPayload.Id<>(id("record_list"));
        public static final PacketCodec<PacketByteBuf, PayloadRecordList> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> {
                            buf.writeVarInt(value.entries().size());
                            for (RecordListEntry e : value.entries()) {
                                buf.writeString(e.fileName());
                                buf.writeVarLong(e.size());
                                buf.writeVarLong(e.lastModified());
                                buf.writeVarInt(e.events());
                            }
                        },
                        buf -> {
                            int count = buf.readVarInt();
                            List<RecordListEntry> entries = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
                            for (int i = 0; i < count; i++) {
                                String name = buf.readString(32767);
                                long size = buf.readVarLong();
                                long modified = buf.readVarLong();
                                int events = buf.readVarInt();
                                entries.add(new RecordListEntry(name, size, modified, events));
                            }
                            return new PayloadRecordList(entries);
                        });

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ==================== S2C：录制数据分片 ====================

    /** 服务端→客户端：录制文件数据分片。 */
    public record PayloadRecordingData(String fileName, int chunkIndex, boolean last, byte[] payload)
            implements CustomPayload {
        public static final CustomPayload.Id<PayloadRecordingData> ID = new CustomPayload.Id<>(id("recording_data"));
        public static final PacketCodec<PacketByteBuf, PayloadRecordingData> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> {
                            buf.writeString(value.fileName());
                            buf.writeVarInt(value.chunkIndex());
                            buf.writeBoolean(value.last());
                            buf.writeByteArray(value.payload());
                        },
                        buf -> new PayloadRecordingData(
                                buf.readString(32767),
                                buf.readVarInt(),
                                buf.readBoolean(),
                                buf.readByteArray()));

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ==================== S2C：打开录制库信号 ====================

    /** 服务端→客户端：打开录制库 GUI 的信号包（无正文）。 */
    public record PayloadOpenRecordLibrary() implements CustomPayload {
        public static final CustomPayload.Id<PayloadOpenRecordLibrary> ID = new CustomPayload.Id<>(id("open_record_library"));
        public static final PacketCodec<PacketByteBuf, PayloadOpenRecordLibrary> CODEC =
                PacketCodec.ofStatic(
                        (buf, value) -> { },
                        buf -> new PayloadOpenRecordLibrary());

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
