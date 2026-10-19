package dev.worldreplay.fabric1201.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端回放可视化（Fabric 1.20.1）。
 *
 * <p>重组分片录制数据，通过 {@link dev.worldreplay.core.event.EventDecoder} 解码为 core
 * {@code Event}，按 tick 播放；当前版本把回放事件打印到日志，图形化可视化留待后续扩展。</p>
 */
public final class ReplayVisualizer {

    /** 文件名 -> 累积中分片（字节列表），重组完成后立即移除。 */
    private static final Map<String, List<byte[]>> CHUNKS = new ConcurrentHashMap<>();
    /** 文件名 -> 最后一片索引（用于判断是否齐全）。 */
    private static final Map<String, Integer> LAST_INDEX = new ConcurrentHashMap<>();
    /** 文件名 -> 首片到达时间（ms），用于超时清理，防止异常分片堆积。 */
    private static final Map<String, Long> FIRST_SEEN = new ConcurrentHashMap<>();
    /** 分片重组超时（ms）：超过后视为传输失败，丢弃累积数据。 */
    private static final long CHUNK_TIMEOUT_MS = 30_000L;
    /** 同时累积中的传输上限，防止内存被撑爆。 */
    private static final int MAX_PENDING_TRANSFERS = 8;

    private ReplayVisualizer() {}

    /** 接收单片录制数据，齐全后解码并启动回放。 */
    public static void receiveChunk(String fileName, int chunkIndex, boolean last, byte[] payload) {
        evictStale();
        if (!CHUNKS.containsKey(fileName) && CHUNKS.size() >= MAX_PENDING_TRANSFERS) {
            sendClientMessage("[WorldReplay] 传输任务过多，忽略 " + fileName);
            return;
        }
        FIRST_SEEN.putIfAbsent(fileName, System.currentTimeMillis());
        List<byte[]> parts = CHUNKS.computeIfAbsent(fileName, k -> new ArrayList<>());
        while (parts.size() <= chunkIndex) parts.add(null);
        parts.set(chunkIndex, payload);
        if (last) LAST_INDEX.put(fileName, chunkIndex);
        if (isComplete(fileName)) {
            byte[] data = assemble(fileName);
            cleanup(fileName);
            startReplay(fileName, data);
        }
    }

    /** 清理超过超时时间的未完成传输。 */
    private static void evictStale() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : FIRST_SEEN.entrySet()) {
            if (now - e.getValue() > CHUNK_TIMEOUT_MS) {
                cleanup(e.getKey());
            }
        }
    }

    private static void cleanup(String fileName) {
        CHUNKS.remove(fileName);
        LAST_INDEX.remove(fileName);
        FIRST_SEEN.remove(fileName);
    }

    private static boolean isComplete(String fileName) {
        Integer last = LAST_INDEX.get(fileName);
        if (last == null) return false;
        List<byte[]> parts = CHUNKS.get(fileName);
        if (parts == null) return false;
        for (int i = 0; i <= last; i++) {
            if (parts.get(i) == null) return false;
        }
        return true;
    }

    private static byte[] assemble(String fileName) {
        List<byte[]> parts = CHUNKS.get(fileName);
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** 解码并按 tick 回放事件（当前以聊天栏输出为准）。 */
    public static void startReplay(String fileName, byte[] data) {
        try {
            // 完整文件 = header + 逐条事件体（与 RecordingSession.export() 对应）
            java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(data));
            byte formatVersion = dev.worldreplay.core.event.EventDecoder.readHeader(in);
            if (formatVersion != 1) {
                sendClientMessage("[WorldReplay] 不支持的录制格式版本: " + formatVersion);
                return;
            }
            int count = 0;
            dev.worldreplay.core.event.Event ev;
            while ((ev = dev.worldreplay.core.event.EventDecoder.readFrom(in)) != null) {
                count++;
                MinecraftClient.getInstance().player.sendMessage(
                        Text.literal("[WorldReplay] t" + ev.tick() + " " + ev.type()
                                + " @ " + String.format("%.1f %.1f %.1f", ev.x(), ev.y(), ev.z())),
                        false);
            }
            sendClientMessage("[WorldReplay] 回放完成: " + fileName + " 共 " + count + " 个事件");
        } catch (Exception ex) {
            sendClientMessage("[WorldReplay] 回放失败: " + ex.getMessage());
        }
    }

    /** 客户端聊天栏输出（player 可能为 null 时降级到日志）。 */
    private static void sendClientMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        } else {
            org.slf4j.LoggerFactory.getLogger(ReplayVisualizer.class).info(message);
        }
    }

    /** 收到分片后尝试启动回放（若文件已在本地齐全）。 */
    public static void maybeStartReplay(ClientPlayerEntity player) {
        // 回放启动由 receiveChunk() 在分片齐全时直接调用 startReplay() 完成；
        // 此方法保留以兼容旧调用方，当前为空操作。
    }
}
