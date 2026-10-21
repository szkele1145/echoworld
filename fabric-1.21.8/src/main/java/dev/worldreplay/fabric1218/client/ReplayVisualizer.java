package dev.worldreplay.fabric1218.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端回放可视化（Fabric 1.21.8）。
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
    /**
     * 单次传输允许的最大分片数。
     *
     * <p>服务端按 24 KiB 切片，{@value #MAX_CHUNKS} 片约 480 MiB——远大于任何
     * 实际录制，同时挡住"发一个 chunkIndex 天文数字的包"这类攻击：
     * 旧实现 {@code while (parts.size() <= chunkIndex) parts.add(null)}
     * 会被 {@code chunkIndex = 2_000_000_000} 直接撑成 20 亿个槽位（约 8 GiB）→ OOM。</p>
     */
    private static final int MAX_CHUNKS = 20_000;
    /** 单次传输允许的最大总字节数（与 {@link #MAX_CHUNKS} × 24 KiB 对齐）。 */
    private static final long MAX_TOTAL_BYTES = (long) MAX_CHUNKS * 24 * 1024;

    private ReplayVisualizer() {}

    /** 接收单片录制数据，齐全后解码并启动回放。 */
    public static void receiveChunk(String fileName, int chunkIndex, boolean last, byte[] payload) {
        evictStale();
        // 分片索引越界直接丢弃：不能让客户端按服务端给的索引分配这么多槽位
        if (chunkIndex < 0 || chunkIndex >= MAX_CHUNKS) {
            sendClientMessage("[WorldReplay] 分片索引越界，丢弃: " + chunkIndex);
            return;
        }
        if (payload == null || payload.length > 24 * 1024) {
            sendClientMessage("[WorldReplay] 分片过大，丢弃 " + fileName);
            cleanup(fileName);
            return;
        }
        if (!CHUNKS.containsKey(fileName) && CHUNKS.size() >= MAX_PENDING_TRANSFERS) {
            sendClientMessage("[WorldReplay] 传输任务过多，忽略 " + fileName);
            return;
        }
        FIRST_SEEN.putIfAbsent(fileName, System.currentTimeMillis());
        List<byte[]> parts = CHUNKS.computeIfAbsent(fileName, k -> new ArrayList<>());
        while (parts.size() <= chunkIndex) parts.add(null);
        parts.set(chunkIndex, payload);
        // 累计字节数超限：丢弃整次传输（防止多片叠加撑爆内存）
        long total = 0;
        for (byte[] p : parts) {
            if (p != null) total += p.length;
            if (total > MAX_TOTAL_BYTES) {
                sendClientMessage("[WorldReplay] 录制数据超过上限，丢弃 " + fileName);
                cleanup(fileName);
                return;
            }
        }
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
        if (parts == null) return new byte[0];
        long total = 0;
        for (byte[] p : parts) {
            if (p != null) total += p.length;
        }
        // 双保险：receiveChunk 已按片累计校验，这里再挡一次（并发下状态可能已变）
        if (total > MAX_TOTAL_BYTES || total > Integer.MAX_VALUE - 8) {
            return new byte[0];
        }
        byte[] out = new byte[(int) total];
        int off = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
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
            int skipped = 0;
            dev.worldreplay.core.event.Event ev;
            // 注意：readFrom 返回 null 表示"这一条无效"（tick<0 / type 空 /
            // 坐标非有限值），不是"流结束"。原来的循环条件写成 != null，
            // 于是遇到第一条无效事件就整体退出——后面所有事件都不播了。
            // 流结束由 IOException（EOFException）表示，这里靠 catch 收尾。
            while (true) {
                try {
                    ev = dev.worldreplay.core.event.EventDecoder.readFrom(in);
                } catch (java.io.EOFException eof) {
                    break;   // 正常流结束
                }
                if (ev == null) {
                    skipped++;
                    continue;   // 跳过这一条，继续读下一条
                }
                count++;
                MinecraftClient.getInstance().player.sendMessage(
                        Text.literal("[WorldReplay] t" + ev.tick() + " " + ev.type()
                                + " @ " + String.format("%.1f %.1f %.1f", ev.x(), ev.y(), ev.z())),
                        false);
            }
            sendClientMessage("[WorldReplay] 回放完成: " + fileName + " 共 " + count + " 个事件"
                    + (skipped > 0 ? "（跳过 " + skipped + " 条无效事件）" : ""));
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
