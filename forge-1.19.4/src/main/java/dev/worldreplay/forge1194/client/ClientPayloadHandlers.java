package dev.worldreplay.forge1194.client;

import dev.worldreplay.forge1194.RecordListPackets;
import dev.worldreplay.forge1194.WorldReplayNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 客户端网络包处理器（Forge 1.19.4）。
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
    public static void handleRecordList(RecordListPackets.RecordList list,
                                        Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyRecordList(list));
        context.setPacketHandled(true);
    }

    /** 服务端→客户端：单片录制数据（可能为分片，需重组）。 */
    public static void handleRecordingData(RecordListPackets.RecordingData data,
                                           Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyRecordingData(data));
        context.setPacketHandled(true);
    }

    /** 服务端→客户端：打开录制库信号。 */
    public static void handleOpenRecordLibrary(RecordListPackets.OpenRecordLibrary msg,
                                                Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> openRecordLibrary());
        context.setPacketHandled(true);
    }

    // ---- 在客户端线程上执行的落地逻辑 ----

    /** 落地：录制文件列表。 */
    public static void applyRecordList(RecordListPackets.RecordList list) {
        List<Entry> entries = new ArrayList<>(list.entries.size());
        for (RecordListPackets.RecordListEntry e : list.entries) {
            entries.add(new Entry(e.fileName, e.size, e.lastModified, e.events));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        RecordingScreen.setEntries(entries);
    }

    /** 落地：录制数据分片。 */
    public static void applyRecordingData(RecordListPackets.RecordingData data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ReplayVisualizer.receiveChunk(data.fileName, data.chunkIndex, data.last, data.payload);
        ReplayVisualizer.maybeStartReplay(mc.player);
    }

    /** 落地：打开录制库 GUI。 */
    public static void openRecordLibrary() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new RecordingScreen());
    }

    // ---- 客户端→服务端 ----

    /** 客户端→服务端：请求录制文件列表。 */
    public static void requestRecordList(Minecraft mc) {
        WorldReplayNetwork.CHANNEL.sendToServer(new RecordListPackets.RequestRecordList());
    }

    /** 客户端→服务端：请求录制操作（重放/删除）。 */
    public static void requestRecording(Minecraft mc, String fileName, int action) {
        WorldReplayNetwork.CHANNEL.sendToServer(new RecordListPackets.RequestRecording(fileName, action));
    }

    /** 客户端→服务端：请求重命名录制文件。 */
    public static void requestRename(Minecraft mc, String oldName, String newName) {
        WorldReplayNetwork.CHANNEL.sendToServer(new RecordListPackets.RequestRename(oldName, newName));
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
