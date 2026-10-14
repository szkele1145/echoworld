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

    /** 文件分片聚合：fileName -> 已收字节（同连接 TCP 保序，无需乱序重组）。 */
    private static final Map<String, ByteArrayOutputStream> incoming = new HashMap<>();

    /** S2C RecordList：暂存列表，主线程里更新已开的屏或开新屏。 */
    public static void handleRecordList(RecordListPackets.RecordList payload) {
        pendingList = List.copyOf(payload.entries());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            List<RecordListPackets.RecordListEntry> list = pendingList;
            pendingList = null;
            if (list == null) return;   // 重复处理已被消费，防御
            if (mc.screen instanceof RecordingScreen screen) {
                screen.updateFiles(list);   // GUI 已开（如删除后刷新）
            } else if (mc.player != null) {
                mc.setScreen(new RecordingScreen(list));   // O 键 / 命令请求后首次开屏
            }
        });
    }

    /** S2C RecordingData：按文件名聚合分片，收齐后解码并开启回放。 */
    public static void handleRecordingData(RecordListPackets.RecordingData payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            String fileName = payload.fileName();
            try {
                ByteArrayOutputStream bos = incoming.computeIfAbsent(
                        fileName, k -> new ByteArrayOutputStream());
                bos.write(payload.data());
                if (!payload.last()) return;   // 还有后续分片

                incoming.remove(fileName);
                byte[] full = bos.toByteArray();
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(full));
                EventDecoder.readHeader(in);   // 校验 magic（失败抛异常）
                List<Event> events = new ArrayList<>();
                while (in.available() > 0) {
                    Event ev = EventDecoder.readFrom(in);
                    if (ev != null) events.add(ev);
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
