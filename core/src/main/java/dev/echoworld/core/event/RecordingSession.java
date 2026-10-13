package dev.echoworld.core.event;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 录制会话：从 startRecord 到 stopRecord 之间捕获的所有事件的容器。
 *
 * <p>非线程安全，单写者语义：事件追加只允许发生在服务端主线程
 * （或录制线程）上，读取方在会话停止后进行。</p>
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

    /** 追加一条事件；无效事件（tick<0 或 type 空）被忽略。 */
    public boolean append(Event event) {
        if (!recording || event == null || !event.isValid()) {
            return false;
        }
        events.add(event);
        return true;
    }

    /** 停止录制，返回已捕获的事件数。 */
    public int stopRecord(long currentTick) {
        recording = false;
        endTick = currentTick;
        return events.size();
    }

    /** 是否仍在录制。 */
    public boolean isRecording() {
        return recording;
    }

    /** 已捕获事件数。 */
    public int eventCount() {
        return events.size();
    }

    /** 会话时长（tick）；未停止时以最后一条事件的 tick 近似。 */
    public long durationTicks() {
        if (endTick >= 0) {
            return endTick - startTick;
        }
        long last = startTick;
        for (Event e : events) {
            if (e.tick() > last) {
                last = e.tick();
            }
        }
        return last - startTick;
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
    public List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * 导出为完整二进制：header + 逐条事件体。
     * 读取方用 {@link EventDecoder#readHeader} + 循环 {@link EventDecoder#readFrom} 消费。
     */
    public byte[] export() throws IOException {
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
