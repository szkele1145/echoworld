package dev.worldreplay.core.event;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 录制会话：从 startRecord 到 stopRecord 之间捕获的所有事件的容器。
 *
 * <p><b>线程安全</b>：事件追加与服务端控制的 {@link #stopRecord} /
 * {@link #export} <b>可能来自不同线程</b>——
 * NeoForge 的退服事件在主线程，而 Fabric 的 {@code DISCONNECT} 回调
 * 可能落在网络线程。旧实现用裸 {@code ArrayList} 且注释写"单写者语义"，
 * 但实际并发下 {@code export()} 遍历时主线程 {@code append} 会抛
 * {@link java.util.ConcurrentModificationException}，或者导出到写了一半的状态。
 * 现在所有访问都经 {@code synchronized} 保护。</p>
 */
public class RecordingSession {

    private final long startTick;
    private final List<Event> events = new ArrayList<>();
    private String sessionId;
    private boolean recording;
    private long endTick = -1;

    public RecordingSession(long startTick, String sessionId) {
        this.startTick = startTick;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.recording = true;
    }

    /** 开始录制（便捷工厂）。 */
    public static RecordingSession start(long currentTick, String sessionId) {
        return new RecordingSession(currentTick, sessionId);
    }

    /** 追加一条事件；无效事件（tick<0 / type 空 / 坐标非有限值）被忽略。 */
    public synchronized boolean append(Event event) {
        if (!recording || event == null || !event.isValid()) {
            return false;
        }
        events.add(event);
        return true;
    }

    /** 停止录制，返回已捕获的事件数。 */
    public synchronized int stopRecord(long currentTick) {
        recording = false;
        endTick = currentTick;
        return events.size();
    }

    /** 是否仍在录制。 */
    public synchronized boolean isRecording() {
        return recording;
    }

    /** 已捕获事件数。 */
    public synchronized int eventCount() {
        return events.size();
    }

    /** 会话时长（tick）；未停止时以最后一条事件的 tick 近似（O(1)：事件严格非降序）。 */
    public synchronized long durationTicks() {
        if (endTick >= 0) {
            return Math.max(0, endTick - startTick);
        }
        if (events.isEmpty()) {
            return 0;
        }
        return Math.max(0, events.get(events.size() - 1).tick() - startTick);
    }

    /** 会话起始 tick。 */
    public long startTick() {
        return startTick;
    }

    /** 会话 id。 */
    public String sessionId() {
        return sessionId;
    }

    /** 事件只读快照。 */
    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * 导出为完整二进制：header + 逐条事件体。
     * 读取方用 {@link EventDecoder#readHeader} + 循环 {@link EventDecoder#readFrom} 消费。
     *
     * <p>未调用 {@link #stopRecord(long)} 时仍可导出已捕获的事件，但 {@code endTick} 为 -1，
     * 下游消费者应自行判断（durationTicks() 已对未停止状态做近似处理）。</p>
     */
    public synchronized byte[] export() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + events.size() * 96);
        DataOutputStream out = new DataOutputStream(bos);
        EventEncoder.writeHeader(out);
        for (Event e : events) {
            EventEncoder.writeTo(e, out);
        }
        out.flush();
        return bos.toByteArray();
    }
}
