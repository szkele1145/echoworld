package dev.worldreplay.nf1211.client;

import com.mojang.logging.LogUtils;
import dev.worldreplay.core.event.Event;
import dev.worldreplay.core.event.EventDecoder;
import dev.worldreplay.nf1211.RecordListPackets;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端 S2C 包处理（Bug 1 修复客户端侧）。
 *
 * <p>本类<b>只在客户端加载</b>：{@code RecordControlHandler} 注册 S2C 通道时以
 * 方法引用指向这里，专用服务器上 lambda 永不执行、类永不初始化
 * （Minecraft / Screen 等客户端类不进服务端类路径）。</p>
 *
 * <p>payload handler 在网络线程触发，所有触及 Minecraft 状态的操作
 * （开屏 / 刷新屏 / 开启回放）都经 {@code mc.execute} 切回主线程。</p>
 */
public final class ClientPayloadHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 列表暂存：网络线程写入，主线程读取后清空。 */
    private static volatile List<RecordListPackets.RecordListEntry> pendingList;

    /**
     * 是否“本次列表下发需要开屏”（v0.1.9）。
     *
     * <p>只有 {@link #handleOpenRecordLibrary} 会置位，即只有 {@code /wr gui}
     * 能打开录制库；删除/重命名后的列表刷新不带本标志，只更新已开的屏，
     * 绝不会凭空弹出界面（用户明确要求：不要 O 键自动开屏）。</p>
     */
    private static volatile boolean pendingOpen;

    /** 文件分片聚合：fileName -> 已收字节（同连接 TCP 保序，无需乱序重组）。 */
    private static final Map<String, ByteArrayOutputStream> incoming = new HashMap<>();

    /**
     * 单次传输允许累积的最大字节数（480 MiB）。
     *
     * <p>客户端<b>不能信任服务端</b>：恶意服务器可以一直发分片，
     * {@link ByteArrayOutputStream} 会一路涨到 OOM。正常录制远小于这个量。</p>
     */
    private static final long MAX_RECORDING_BYTES = 480L * 1024 * 1024;

    /**
     * 单次回放允许的事件数上限。
     *
     * <p>解码后的 {@link Event} 对象每个约 100 字节（对象头 + 8 个引用 +
     * 3 个 double + 字符串），50 万条约 50 MB，200 万条约 200 MB。
     * 上限定在 200 万：正常录制远小于此，超出则截断并告警，
     * 避免客户端被一份畸形录制拖到 OOM。</p>
     */
    private static final int MAX_EVENTS = 2_000_000;

    /**
     * S2C OpenRecordLibrary：/wr gui 的开屏信号。
     *
     * <p>只置标志、不直接开屏——真正的界面构建发生在紧随其后的 {@link #handleRecordList}
     * 里（需要文件列表作为构造参数）。在网络线程写 volatile，保证先于列表包可见。</p>
     */
    public static void handleOpenRecordLibrary(RecordListPackets.OpenRecordLibrary payload) {
        pendingOpen = true;
    }

    /** S2C RecordList：暂存列表，主线程里刷新已开的屏，或按 {@link #pendingOpen} 开新屏。 */
    public static void handleRecordList(RecordListPackets.RecordList payload) {
        pendingList = List.copyOf(payload.entries());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            List<RecordListPackets.RecordListEntry> list = pendingList;
            pendingList = null;
            if (list == null) return;   // 重复处理已被消费，防御
            boolean open = pendingOpen;
            pendingOpen = false;
            if (mc.screen instanceof RecordingScreen screen) {
                screen.updateFiles(list);   // GUI 已开（如删除/重命名后刷新）
            } else if (open && mc.player != null) {
                mc.setScreen(new RecordingScreen(list));   // 仅 /wr gui 触发
            }
            // open=false 且未开屏：丢弃（例如别人执行命令时自己刚好收了列表）
        });
    }

    /** S2C RecordingData：按文件名聚合分片，chunkIndex==0 重置旧缓冲（防双击拼接污染），收齐后解码并开启回放。 */
    public static void handleRecordingData(RecordListPackets.RecordingData payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            String fileName = payload.fileName();
            try {
                if (payload.chunkIndex() == 0) {
                    incoming.remove(fileName);   // 新一轮传输：丢弃旧聚合（双击 Replay 的污染防护）
                }
                ByteArrayOutputStream bos = incoming.computeIfAbsent(
                        fileName, k -> new ByteArrayOutputStream());
                // 总字节上限：不信任服务端——恶意服务器可以持续发片，
                // ByteArrayOutputStream 会一路涨到 OOM。这里按实际写入量挡住。
                if (bos.size() > MAX_RECORDING_BYTES) {
                    incoming.remove(fileName);
                    LOGGER.warn("[WorldReplay] Recording {} exceeds size limit, dropped", fileName);
                    return;
                }
                bos.write(payload.data());
                if (!payload.last()) return;   // 还有后续分片

                incoming.remove(fileName);
                byte[] full = bos.toByteArray();
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(full));
                EventDecoder.readHeader(in);   // 校验 magic（失败抛异常）
                List<Event> events = new ArrayList<>();
                while (in.available() > 0) {
                    Event ev = EventDecoder.readFrom(in);
                    if (ev != null) {
                        // 事件数上限：480 MiB 的录制可能有上千万条事件，
                        // 每个 Event 对象约 100 字节——全装进 List 就是 GB 级，
                        // 客户端直接 OOM。超出上限就停在这一份（前半段可看）。
                        if (events.size() >= MAX_EVENTS) {
                            LOGGER.warn("[WorldReplay] Recording {} has more than {} events, truncated",
                                    fileName, MAX_EVENTS);
                            break;
                        }
                        events.add(ev);
                    }
                }
                if (events.isEmpty()) {
                    LOGGER.warn("[WorldReplay] Recording {} has no valid events", fileName);
                    return;
                }
                ReplayVisualizer.begin(events);
                if (mc.screen instanceof RecordingScreen) {
                    mc.setScreen(null);   // 关 GUI 看世界内的回放
                }
            } catch (Exception e) {
                incoming.remove(fileName);   // 失败丢弃半成品，避免占内存
                LOGGER.error("[WorldReplay] Failed to load recording {}", fileName, e);
            }
        });
    }

    private ClientPayloadHandlers() {}
}
