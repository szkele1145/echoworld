package dev.worldreplay.forge1201;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WorldReplay Forge 1.20.1 录制列表/控制包定义（服务端与客户端共享荷载结构）。
 *
 * <p>全部包体采用 {@link FriendlyByteBuf} 序列化（Forge 官方映射下
 * {@code PacketByteBuf} 的对应类），字节布局与 Fabric / NeoForge 版完全一致，
 * 因此同一台服务器上不同平台的录制文件可以互通。</p>
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

    /** 录制数据分片（服务端→客户端）：fileName + chunkIndex + last + payload。 */
    public static final class RecordingData {
        public final String fileName;
        public final int chunkIndex;
        public final boolean last;
        public final byte[] payload;

        public RecordingData(String fileName, int chunkIndex, boolean last, byte[] payload) {
            this.fileName = fileName;
            this.chunkIndex = chunkIndex;
            this.last = last;
            this.payload = payload;
        }
    }

    private RecordListPackets() {}

    // ---- FriendlyByteBuf 序列化辅助 ----

    public static FriendlyByteBuf encodeRecordControl(RecordControl ctrl) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeBoolean(ctrl.start);
        buf.writeVarInt(ctrl.players.size());
        for (UUID id : ctrl.players) {
            buf.writeUUID(id);
        }
        return buf;
    }

    public static RecordControl decodeRecordControl(FriendlyByteBuf buf) {
        boolean start = buf.readBoolean();
        int count = buf.readVarInt();
        List<UUID> players = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
        for (int i = 0; i < count; i++) {
            players.add(buf.readUUID());
        }
        return new RecordControl(start, players);
    }

    public static FriendlyByteBuf encodeRecordList(RecordList list) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(list.entries.size());
        for (RecordListEntry e : list.entries) {
            buf.writeUtf(e.fileName);
            buf.writeVarLong(e.size);
            buf.writeVarLong(e.lastModified);
            buf.writeVarInt(e.events);
        }
        return buf;
    }

    public static RecordList decodeRecordList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<RecordListEntry> entries = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
        for (int i = 0; i < count; i++) {
            entries.add(new RecordListEntry(buf.readUtf(32767), buf.readVarLong(),
                    buf.readVarLong(), buf.readVarInt()));
        }
        return new RecordList(entries);
    }

    public static FriendlyByteBuf encodeRequestRecording(RequestRecording req) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(req.fileName);
        buf.writeVarInt(req.action);
        return buf;
    }

    public static RequestRecording decodeRequestRecording(FriendlyByteBuf buf) {
        return new RequestRecording(buf.readUtf(32767), buf.readVarInt());
    }

    public static FriendlyByteBuf encodeRequestRename(RequestRename req) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(req.oldName);
        buf.writeUtf(req.newName);
        return buf;
    }

    public static RequestRename decodeRequestRename(FriendlyByteBuf buf) {
        return new RequestRename(buf.readUtf(32767), buf.readUtf(32767));
    }

    /** 构造本模组的资源标识 {@code worldreplay:<path>}。 */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(WorldReplayForge.MOD_ID, path);
    }
}
