package dev.worldreplay.nf1211;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制列表 / 文件数据通道（Bug 1 修复核心）。
 *
 * <p>背景：录制文件落盘在<b>服务端</b> {@code serverDirectory/worldreplay/recordings}，
 * 客户端本地没有这些文件——旧 GUI 扫描客户端工作目录导致列表永远为空。
 * 现在列表与文件内容全部经网络分发，GUI 只显示服务端数据。</p>
 *
 * <p>通道一览：
 * <ul>
 *   <li>S2C {@link RecordList} —— 服务端扫描 recordings 目录后下发的完整文件列表</li>
 *   <li>S2C {@link OpenRecordLibrary} —— /wr gui 的开屏信号（v0.1.9，取代 O 键）</li>
 *   <li>S2C {@link RecordingData} —— .wrp 文件数据分片（回放请求的响应）</li>
 *   <li>C2S {@link RequestRecordList} —— 请求文件列表（保留以兼容 0.1.8 及更早客户端的 O 键）</li>
 *   <li>C2S {@link RequestRecording} —— 客户端请求回放或删除某文件</li>
 *   <li>C2S {@link RequestRename} —— 客户端请求重命名某文件（v0.1.9）</li>
 * </ul></p>
 *
 * <p>{@link RecordingData} 采用分片设计：原版 S2C 自定义包上限约 32KB
 * （{@code ClientboundCustomPayloadPacket}），单包传整个录制文件必然超限断连，
 * 因此服务端把文件切成 {@link #MAX_CHUNK} 字节的片顺序下发（TCP 保序），
 * 末片 {@code last=true}，客户端按文件名聚合后解码。</p>
 */
public final class RecordListPackets {
    private RecordListPackets() {}

    /** RecordingData 单片字节数（须小于原版 S2C 自定义包 32767 字节上限）。 */
    public static final int MAX_CHUNK = 24 * 1024;

    // ---- 数据记录（非 payload，仅作列表条目）----

    /** 一条录制文件条目：文件名 + 字节数 + 修改时间毫秒 + 事件数。 */
    public record RecordListEntry(String fileName, long size, long modified, int events) {}

    // ---- S2C：文件列表 ----

    /** S2C：完整文件列表（GUI 直接用它初始化/刷新）。 */
    public record RecordList(List<RecordListEntry> entries) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RecordList> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        WorldReplayNeoForge.MOD_ID, "record_list"));

        public static final StreamCodec<FriendlyByteBuf, RecordList> CODEC = new StreamCodec<>() {
            @Override
            public RecordList decode(FriendlyByteBuf buf) {
                int n = buf.readVarInt();
                List<RecordListEntry> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    list.add(new RecordListEntry(buf.readUtf(), buf.readLong(), buf.readLong(), buf.readVarInt()));
                }
                return new RecordList(list);
            }

            @Override
            public void encode(FriendlyByteBuf buf, RecordList p) {
                buf.writeVarInt(p.entries().size());
                for (RecordListEntry e : p.entries()) {
                    buf.writeUtf(e.fileName());
                    buf.writeLong(e.size());
                    buf.writeLong(e.modified());
                    buf.writeVarInt(e.events());
                }
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- S2C：文件数据分片 ----

    /**
     * S2C：.wrp 文件数据分片。{@code last=true} 表示该文件的最后一片。
     * 客户端按 fileName 聚合，收齐后整体解码进入回放。
     */
    /**
     * S2C：.wrp 文件数据分片。{@code chunkIndex==0} 表示新文件开始
     * （客户端据此重置旧聚合缓冲——防快速双击 Replay 时两份数据拼接污染）。
     */
    public record RecordingData(String fileName, int chunkIndex, boolean last, byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RecordingData> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        WorldReplayNeoForge.MOD_ID, "recording_data"));

        public static final StreamCodec<FriendlyByteBuf, RecordingData> CODEC = new StreamCodec<>() {
            @Override
            public RecordingData decode(FriendlyByteBuf buf) {
                String fileName = buf.readUtf();
                int chunkIndex = buf.readVarInt();
                boolean last = buf.readBoolean();
                byte[] data = buf.readByteArray();
                return new RecordingData(fileName, chunkIndex, last, data);
            }

            @Override
            public void encode(FriendlyByteBuf buf, RecordingData p) {
                buf.writeUtf(p.fileName());
                buf.writeVarInt(p.chunkIndex());
                buf.writeBoolean(p.last());
                buf.writeByteArray(p.data());
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- C2S：请求列表 ----

    /** C2S：请求服务端下发文件列表（O 键打开 GUI / 打开前刷新）。 */
    public record RequestRecordList() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestRecordList> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        WorldReplayNeoForge.MOD_ID, "request_record_list"));

        public static final StreamCodec<FriendlyByteBuf, RequestRecordList> CODEC = new StreamCodec<>() {
            @Override
            public RequestRecordList decode(FriendlyByteBuf buf) {
                return new RequestRecordList();
            }

            @Override
            public void encode(FriendlyByteBuf buf, RequestRecordList p) {
                // 无字段
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- C2S：请求回放/删除（v0.1.8 格式，保持 2 字段不动）----

    /** C2S：请求服务端执行回放（回传文件数据）或删除（回传刷新后的列表）。 */
    public record RequestRecording(String fileName, int action) implements CustomPacketPayload {
        /** 回放：服务端把文件分片经 {@link RecordingData} 发回。 */
        public static final int ACTION_REPLAY = 0;
        /** 删除：服务端删文件后重发 {@link RecordList} 刷新 GUI。 */
        public static final int ACTION_DELETE = 1;

        public static final CustomPacketPayload.Type<RequestRecording> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        WorldReplayNeoForge.MOD_ID, "request_recording"));

        public static final StreamCodec<FriendlyByteBuf, RequestRecording> CODEC = new StreamCodec<>() {
            @Override
            public RequestRecording decode(FriendlyByteBuf buf) {
                return new RequestRecording(buf.readUtf(), buf.readVarInt());
            }

            @Override
            public void encode(FriendlyByteBuf buf, RequestRecording p) {
                buf.writeUtf(p.fileName());
                buf.writeVarInt(p.action());
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- C2S：请求重命名（v0.1.9 新增，独立 payload：老客户端不会发，新客户端对老服务端也兼容）----

    /** C2S：重命名一条录制（服务端改名后重发 {@link RecordList} 刷新 GUI）。 */
    public record RequestRename(String fileName, String newName) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestRename> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        WorldReplayNeoForge.MOD_ID, "request_rename"));

        public static final StreamCodec<FriendlyByteBuf, RequestRename> CODEC = new StreamCodec<>() {
            @Override
            public RequestRename decode(FriendlyByteBuf buf) {
                return new RequestRename(buf.readUtf(), buf.readUtf());
            }

            @Override
            public void encode(FriendlyByteBuf buf, RequestRename p) {
                buf.writeUtf(p.fileName());
                buf.writeUtf(p.newName());
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    // ---- S2C：请求客户端打开录制库（v0.1.9，取代 O 键）----

    /**
     * S2C：请客户端打开录制管理屏。
     *
     * <p>v0.1.9 起录制库<b>只能</b>由 {@code /wr gui} 打开：服务端先发本包
     * 再发 {@link RecordList}，客户端据此决定“开新屏”还是“仅刷新已开的屏”，
     * 不再由客户端按键触发。无字段，独立通道（老客户端收到未知 optional 通道直接忽略）。</p>
     */
    public record OpenRecordLibrary() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<OpenRecordLibrary> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                        WorldReplayNeoForge.MOD_ID, "open_record_library"));

        public static final StreamCodec<FriendlyByteBuf, OpenRecordLibrary> CODEC = new StreamCodec<>() {
            @Override
            public OpenRecordLibrary decode(FriendlyByteBuf buf) {
                return new OpenRecordLibrary();
            }

            @Override
            public void encode(FriendlyByteBuf buf, OpenRecordLibrary p) {
                // 无字段
            }
        };

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
