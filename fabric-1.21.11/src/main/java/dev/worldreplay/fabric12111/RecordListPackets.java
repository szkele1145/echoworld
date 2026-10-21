package dev.worldreplay.fabric12111;

import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WorldReplay Fabric 1.21.8 录制列表/控制包定义（服务端与客户端共享荷载结构）。
 *
 * <p>全部包体采用 {@link PacketByteBuf} 序列化，对应客户端
 * {@link dev.worldreplay.fabric12111.client.ClientPayloadHandlers} 中的接收器。
 * 字段与 NeoForge 1.21.1 版保持一致，以便跨平台录制互通。</p>
 */
public final class RecordListPackets {

    /** 录制控制：{@code start} 布尔 + 目标玩家 UUID 列表。 */
    public static final class RecordControl {
        public final boolean start;
        public final List<UUID> players;

        public RecordControl(boolean start, List<UUID> players) {
            this.start = start;
            this.players = players;
        }
    }

    /** 录制列表条目（文件名、大小、最后修改时间、事件数）。 */
    public static final class RecordListEntry {
        public final String fileName;
        public final long size;
        public final long lastModified;
        /** 录制内的事件条数（与 NeoForge 侧 RecordListEntry 的第 4 个字段对齐）。 */
        public final int events;

        public RecordListEntry(String fileName, long size, long lastModified, int events) {
            this.fileName = fileName;
            this.size = size;
            this.lastModified = lastModified;
            this.events = events;
        }
    }

    /** 录制列表（服务端→客户端，回应 RequestRecordList）。 */
    public static final class RecordList {
        public final List<RecordListEntry> entries;

        public RecordList(List<RecordListEntry> entries) {
            this.entries = entries;
        }
    }

    /** 请求录制列表（客户端→服务端，空载荷）。 */
    public static final class RequestRecordList {
        public RequestRecordList() {}
    }

    /** 请求录制操作（客户端→服务端）：fileName + action。 */
    public static final class RequestRecording {
        public static final int ACTION_REPLAY = 0;
        public static final int ACTION_DELETE = 1;
        public final String fileName;
        public final int action;

        public RequestRecording(String fileName, int action) {
            this.fileName = fileName;
            this.action = action;
        }
    }

    /** 请求重命名录制文件（客户端→服务端）：oldName + newName。 */
    public static final class RequestRename {
        public final String oldName;
        public final String newName;

        public RequestRename(String oldName, String newName) {
            this.oldName = oldName;
            this.newName = newName;
        }
    }

    /** 打开录制库信号（服务端→客户端，空载荷）。 */
    public static final class OpenRecordLibrary {
        public OpenRecordLibrary() {}
    }

    private RecordListPackets() {}

    // ---- PacketByteBuf 序列化辅助 ----

    public static PacketByteBuf encodeRecordControl(RecordControl ctrl) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(ctrl.start);
        buf.writeVarInt(ctrl.players.size());
        for (UUID id : ctrl.players) {
            buf.writeUuid(id);
        }
        return buf;
    }

    public static RecordControl decodeRecordControl(PacketByteBuf buf) {
        boolean start = buf.readBoolean();
        int count = buf.readVarInt();
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(buf.readUuid());
        }
        return new RecordControl(start, players);
    }

    public static PacketByteBuf encodeRecordList(RecordList list) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(list.entries.size());
        for (RecordListEntry e : list.entries) {
            buf.writeString(e.fileName);
            buf.writeVarLong(e.size);
            buf.writeVarLong(e.lastModified);
            buf.writeVarInt(e.events);
        }
        return buf;
    }

    public static RecordList decodeRecordList(PacketByteBuf buf) {
        int count = buf.readVarInt();
        List<RecordListEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new RecordListEntry(buf.readString(32767), buf.readVarLong(),
                    buf.readVarLong(), buf.readVarInt()));
        }
        return new RecordList(entries);
    }

    public static PacketByteBuf encodeRequestRecording(RequestRecording req) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(req.fileName);
        buf.writeVarInt(req.action);
        return buf;
    }

    public static RequestRecording decodeRequestRecording(PacketByteBuf buf) {
        return new RequestRecording(buf.readString(32767), buf.readVarInt());
    }

    public static PacketByteBuf encodeRequestRename(RequestRename req) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(req.oldName);
        buf.writeString(req.newName);
        return buf;
    }

    public static RequestRename decodeRequestRename(PacketByteBuf buf) {
        return new RequestRename(buf.readString(32767), buf.readString(32767));
    }
}
