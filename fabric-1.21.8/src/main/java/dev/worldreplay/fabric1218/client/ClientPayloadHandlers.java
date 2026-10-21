package dev.worldreplay.fabric1218.client;

import dev.worldreplay.fabric1218.WorldReplayPayloads;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadOpenRecordLibrary;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordList;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRecordingData;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRequestRecordList;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRequestRecording;
import dev.worldreplay.fabric1218.WorldReplayPayloads.PayloadRequestRename;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端网络包处理器（Fabric 1.21.8）。
 *
 * <p>1.21.x 适配：收包用 {@code ClientPlayNetworking.registerReceiver(Type, handler)}，
 * 发包直接 {@code send(payload)}，不再手工构造 PacketByteBuf。</p>
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    /** 注册客户端侧的 payload 接收器。 */
    public static void register() {
        ClientPlayNetworking.registerReceiver(PayloadRecordList.ID,
                (payload, context) -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    List<Entry> entries = new ArrayList<>(payload.entries().size());
                    for (WorldReplayPayloads.RecordListEntry e : payload.entries()) {
                        entries.add(new Entry(e.fileName(), e.size(), e.lastModified(), e.events()));
                    }
                    if (client.player != null) {
                        client.execute(() -> {
                            RecordingScreen.setEntries(entries);
                            // 若 GUI 已打开则刷新；未打开则等待 open_record_library 信号
                        });
                    }
                });

        ClientPlayNetworking.registerReceiver(PayloadRecordingData.ID,
                (payload, context) -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    ReplayVisualizer.receiveChunk(payload.fileName(), payload.chunkIndex(),
                            payload.last(), payload.payload());
                    if (client.player != null) {
                        client.execute(() -> ReplayVisualizer.maybeStartReplay(client.player));
                    }
                });

        ClientPlayNetworking.registerReceiver(PayloadOpenRecordLibrary.ID,
                (payload, context) -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        client.execute(() -> client.setScreen(new RecordingScreen()));
                    }
                });
    }

    /** 客户端→服务端：请求录制文件列表（通过 /wr gui 或 GUI 打开时触发）。 */
    public static void requestRecordList(MinecraftClient client) {
        ClientPlayNetworking.send(new PayloadRequestRecordList());
    }

    /** 客户端→服务端：请求录制操作（重放/删除）。 */
    public static void requestRecording(MinecraftClient client, String fileName, int action) {
        ClientPlayNetworking.send(new PayloadRequestRecording(fileName, action));
    }

    /** 客户端→服务端：请求重命名录制文件。 */
    public static void requestRename(MinecraftClient client, String oldName, String newName) {
        ClientPlayNetworking.send(new PayloadRequestRename(oldName, newName));
    }

    /** 录制文件列表条目（仅客户端显示用）。 */
    public static final class Entry {
        public final String fileName;
        public final long size;
        public final long lastModified;
        /** 录制内的事件条数（与 NeoForge 侧一致）。 */
        public final int events;

        public Entry(String fileName, long size, long lastModified, int events) {
            this.fileName = fileName;
            this.size = size;
            this.lastModified = lastModified;
            this.events = events;
        }

        @Override
        public String toString() {
            return fileName + " (" + size + " bytes)";
        }
    }
}
