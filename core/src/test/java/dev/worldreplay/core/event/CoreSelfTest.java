package dev.worldreplay.core.event;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * core 回归自检：零依赖（JUnit 走 mavenCentral 不可达），java -cp 直接跑。
 * 覆盖二轮深查修掉的边界：
 *  - ReplayEngine: seek 后 play 的一致性 / stop 后 replay / speed 钳制 / 空时间线
 *  - RecordingSession: stopRecord 后 append 拒绝 / export 未停止可用
 *  - Event 编解码往返 / 截断防御 / null 维度兜底
 * 退出码 0 = 全过；非 0 = 有失败（stderr 列出）。
 */
public final class CoreSelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws IOException {
        replaySeekThenPlay();
        replayStopThenReplay();
        replaySpeedClamp();
        replayEmptyTimeline();
        replaySlowSpeedAccumulator();
        sessionAppendAfterStop();
        sessionExportBeforeStop();
        codecRoundTrip();
        codecOversizedTruncation();
        eventNullDimensionFallback();
        decoderSkipsInvalid();

        if (failures > 0) {
            System.err.println("FAILED: " + failures + " test(s)");
            System.exit(1);
        }
        System.out.println("OK: all core self-tests passed");
    }

    // ---- ReplayEngine ----

    /** seek 到中段后 play：跳过中间事件不重放，从 seek 点之后继续。 */
    static void replaySeekThenPlay() {
        ReplayEngine r = new ReplayEngine(events(0, 10, 20, 30));
        r.play();
        r.tick(); // 推进到 1，重放 tick0
        r.pause();
        r.seek(20);
        check("seek落点currentEvent为被跳过的最后一条", r.getCurrentEvent() != null && r.getCurrentEvent().tick() == 10);
        r.play();
        int n = r.tick(); // 推进到 21；tick20 是 seek 后首条待重放事件，应在此重放
        check("seek后currentTick", r.getCurrentTick() == 21);
        check("seek后play只重放seek点事件", n == 1, "replayed=" + n);
        check("重放的是tick20", r.getCurrentEvent() != null && r.getCurrentEvent().tick() == 20);
    }

    /** stop 后再 play：从头开始，状态干净。 */
    static void replayStopThenReplay() {
        ReplayEngine r = new ReplayEngine(events(0, 10, 20));
        r.play();
        r.tick();
        r.tick();
        r.stop();
        check("stop后非playing", !r.isPlaying());
        check("stop后游标归零", r.getCurrentTick() == 0);
        r.play();
        check("stop后可重播", r.isPlaying());
        r.tick();
        check("重播从首事件开始", r.getCurrentEvent() != null && r.getCurrentEvent().tick() == 0);
    }

    /** speed 边界：NaN/负/过大都被钳制。 */
    static void replaySpeedClamp() {
        ReplayEngine r = new ReplayEngine(events(0, 5));
        r.setSpeed(Double.NaN);
        check("NaN钳到MIN", r.getSpeed() == ReplayEngine.MIN_SPEED);
        r.setSpeed(-3);
        check("负值钳到MIN", r.getSpeed() == ReplayEngine.MIN_SPEED);
        r.setSpeed(1000);
        check("过大钳到MAX", r.getSpeed() == ReplayEngine.MAX_SPEED);
        r.setSpeed(2.0);
        check("正常值保留", r.getSpeed() == 2.0);
    }

    /** 空时间线：play 不进 playing，tick 恒 0。 */
    static void replayEmptyTimeline() {
        ReplayEngine r = new ReplayEngine(List.of());
        r.play();
        check("空时间线play无效", !r.isPlaying());
        check("空时间线tick返回0", r.tick() == 0);
        check("空时间线endTick=0", r.endTick() == 0);
        r.seek(50);
        check("空时间线seek安全", r.getCurrentTick() == 50);
    }

    /** 0.25x：每 4 tick 才前进 1；0.5x：每 2 tick 前进 1。 */
    static void replaySlowSpeedAccumulator() {
        ReplayEngine r = new ReplayEngine(events(0, 1, 2, 3));
        r.setSpeed(0.25);
        r.play();
        int replayed = 0;
        for (int i = 0; i < 8; i++) replayed += r.tick();
        check("0.25x八tick只前进2格", r.getCurrentTick() == 2, "tick=" + r.getCurrentTick());

        ReplayEngine r2 = new ReplayEngine(events(0, 1, 2, 3, 4));
        r2.setSpeed(0.5);
        r2.play();
        for (int i = 0; i < 4; i++) r2.tick();
        check("0.5x四tick前进2格", r2.getCurrentTick() == 2, "tick=" + r2.getCurrentTick());
    }

    // ---- RecordingSession ----

    /** stopRecord 后 append 一律拒绝。 */
    static void sessionAppendAfterStop() throws IOException {
        RecordingSession s = RecordingSession.start(100, "s1");
        check("初始为录制中", s.isRecording());
        check("append有效事件", s.append(new Event(101, "block_break", "p1", "minecraft:overworld", 1, 2, 3, "{}")));
        check("append null拒绝", !s.append(null));
        check("append无效tick拒绝", !s.append(new Event(-1, "x", "p", "d", 0, 0, 0, "{}")));
        check("append空type拒绝", !s.append(new Event(5, "", "p", "d", 0, 0, 0, "{}")));
        s.stopRecord(200);
        check("stop后非录制中", !s.isRecording());
        check("stop后append拒绝", !s.append(new Event(150, "block_break", "p1", "minecraft:overworld", 0, 0, 0, "{}")));
        check("时长=stop-start", s.durationTicks() == 100);
        check("事件数=1", s.eventCount() == 1);
    }

    /** 未 stopRecord 时 export：能导，解码往返无损。 */
    static void sessionExportBeforeStop() throws IOException {
        RecordingSession s = RecordingSession.start(0, "s2");
        s.append(new Event(1, "block_place", "p1", "minecraft:overworld", 4, 5, 6, "{\"b\":\"stone\"}"));
        s.append(new Event(2, "block_break", "p1", "minecraft:overworld", 4, 5, 6, "{}"));
        byte[] bytes = s.export();
        check("未停止可导出", bytes.length > 3);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        byte ver = EventDecoder.readHeader(in);
        check("header版本=1", ver == 1);
        List<Event> decoded = new ArrayList<>();
        while (in.available() > 0) {
            Event e = EventDecoder.readFrom(in);
            if (e != null) decoded.add(e);
        }
        check("解码两条", decoded.size() == 2, "size=" + decoded.size());
        check("往返tick一致", decoded.get(0).tick() == 1 && decoded.get(1).tick() == 2);
        check("往返data一致", "{\"b\":\"stone\"}".equals(decoded.get(0).data()));
    }

    // ---- Event 编解码 ----

    /** 常规事件 encode→decode 往返。 */
    static void codecRoundTrip() throws IOException {
        Event e = new Event(12345, "entity_move", "uuid-abc", "minecraft:the_nether", 1.5, -2.25, 3.75, "{\"dx\":0.1}");
        byte[] bytes = EventEncoder.encode(e);
        Event back = EventDecoder.decode(bytes);
        check("roundtrip非null", back != null);
        check("roundtrip字段", back.tick() == 12345 && "entity_move".equals(back.type())
                && "uuid-abc".equals(back.playerId()) && "minecraft:the_nether".equals(back.dimension())
                && back.x() == 1.5 && back.y() == -2.25 && back.z() == 3.75
                && "{\"dx\":0.1}".equals(back.data()));
    }

    /** 超长 data 截断：不抛异常、不切多字节字符。 */
    static void codecOversizedTruncation() throws IOException {
        String longData = "{\"blob\":\"" + "烸".repeat(30000) + "\"}"; // 90000 UTF-8 字节
        Event e = new Event(1, "gui_open", "p", "minecraft:overworld", 0, 0, 0, longData);
        byte[] bytes = EventEncoder.encode(e);
        Event back = EventDecoder.decode(bytes);
        check("超长截断可解码", back != null);
        check("截断带标记", back.data().endsWith("…[truncated]"));
    }

    /** 构造器 null 维度兜底为 overworld。 */
    static void eventNullDimensionFallback() {
        Event e = new Event(1, "block_place", "p", null, 0, 0, 0, null);
        check("null维度兜底", "minecraft:overworld".equals(e.dimension()));
        check("null data兜底", "{}".equals(e.data()));
    }

    /** 解码器跳过无效事件（tick<0 / type 空）返回 null。 */
    static void decoderSkipsInvalid() throws IOException {
        Event bad = new Event(-5, "ok", "p", "d", 0, 0, 0, "{}");
        check("负tick encode→decode为null", EventDecoder.decode(EventEncoder.encode(bad)) == null);
        Event bad2 = new Event(5, "", "p", "d", 0, 0, 0, "{}");
        check("空type encode→decode为null", EventDecoder.decode(EventEncoder.encode(bad2)) == null);
    }

    // ---- 工具 ----

    static List<Event> events(long... ticks) {
        List<Event> list = new ArrayList<>();
        for (long t : ticks) {
            list.add(new Event(t, "block_break", "p1", "minecraft:overworld", t, 0, 0, "{}"));
        }
        return list;
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  PASS " + name);
        } else {
            failures++;
            System.err.println("  FAIL " + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }
    }

    private CoreSelfTest() {}
}
