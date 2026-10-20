package dev.worldreplay.fabric1194.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端网络包处理器（Fabric 1.19.4）。
 *
 * <p>接收服务端下发的三类包：
 * <ul>
 *   <li>{@code record_list} —— 录制文件列表，交给 {@link RecordingScreen} 显示</li>
 *   <li>{@code recording_data} —— 录制文件分片，重组后交给 {@link ReplayVisualizer} 回放</li>
 *   <li>{@code open_record_library} —— 信号包，提示打开录制库 GUI</li>
 * </ul></p>
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {}

    /** 服务端→客户端：录制文件列表。 */
    public static void handleRecordList(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        int count = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buf.readString(32767);
            long size = buf.readVarLong();
            long modified = buf.readVarLong();
            int events = buf.readVarInt();
            entries.add(new Entry(name, size, modified, events));
        }
        if (client.player != null) {
            client.execute(() -> {
                RecordingScreen.setEntries(entries);
                // 若 GUI 已打开则刷新；未打开则等待 open_record_library 信号
            });
        }
    }

    /** 服务端→客户端：单片录制数据（可能为分片，需重组）。 */
    public static void handleRecordingData(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        String fileName = buf.readString(32767);
        int chunkIndex = buf.readVarInt();
        boolean last = buf.readBoolean();
        byte[] payload = buf.readByteArray();
        ReplayVisualizer.receiveChunk(fileName, chunkIndex, last, payload);
        if (client.player != null) {
            client.execute(() -> ReplayVisualizer.maybeStartReplay(client.player));
        }
    }

    /** 服务端→客户端：打开录制库信号。 */
    public static void handleOpenRecordLibrary(MinecraftClient client, ClientPlayNetworkHandler handler, PacketByteBuf buf, PacketSender sender) {
        if (client.player != null) {
            client.execute(() -> client.setScreen(new RecordingScreen()));
        }
    }

    /** 客户端→服务端：请求录制文件列表（通过 /wr gui 或 GUI 打开时触发）。 */
    public static void requestRecordList(MinecraftClient client) {
        PacketByteBuf buf = PacketByteBufs.create();
        ClientPlayNetworking.send(WorldReplayFabricClient.id("request_record_list"), buf);
    }

    /** 客户端→服务端：请求录制操作（重放/删除）。 */
    public static void requestRecording(MinecraftClient client, String fileName, int action) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(fileName);
        buf.writeVarInt(action);
        ClientPlayNetworking.send(WorldReplayFabricClient.id("request_recording"), buf);
    }

    /** 客户端→服务端：请求重命名录制文件。 */
    public static void requestRename(MinecraftClient client, String oldName, String newName) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(oldName);
        buf.writeString(newName);
        ClientPlayNetworking.send(WorldReplayFabricClient.id("request_rename"), buf);
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
