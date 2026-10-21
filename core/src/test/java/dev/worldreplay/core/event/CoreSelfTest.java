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

    public static void main(String[] args) throws Exception {
        replaySeekThenPlay();
        replayStopThenReplay();
        replaySpeedClamp();
        replayEmptyTimeline();
        replaySlowSpeedAccumulator();
        replayRestartsAfterFinishing();
        replaySeekIsClampedToTimeline();
        sessionAppendAfterStop();
        sessionExportBeforeStop();
        codecRoundTrip();
        codecOversizedTruncation();
        eventNullDimensionFallback();
        eventRejectsNonFiniteCoords();
        sessionConcurrentAppendAndExport();
        decoderSkipsInvalid();
        eventCounterCountsAndCaches();
        eventCounterMatchesDecoder();

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
        // 空时间线没有时间线范围，seek 被钳到 0（不崩即可；
        // 具体取值无意义，钳到 0 与"空=无内容"语义一致）
        check("空时间线seek安全", r.getCurrentTick() == 0, "tick=" + r.getCurrentTick());
    }

    /**
     * 播到末尾后再按"继续"：必须能从头重播。
     *
     * <p>修复前 advanceTo 播到末尾会置 playing=false，此时游标已在末尾，
     * play() 的 {@code cursor < size} 判定不成立 → 静默无反应，
     * 用户只能先 Stop 再 Play，绕一圈才能重看一遍。</p>
     */
    static void replayRestartsAfterFinishing() {
        ReplayEngine r = new ReplayEngine(events(0, 10, 20));
        r.play();
        for (int i = 0; i < 40; i++) r.tick();   // 足够播完全部
        check("播完后自动停止", !r.isPlaying());
        r.play();                                  // 用户按"继续"
        check("播完后play能重播", r.isPlaying());
        check("重播时游标归零", r.getCurrentTick() == 0);
        int n = r.tick();
        check("重播从头开始", n >= 1 && r.getCurrentEvent() != null
                && r.getCurrentEvent().tick() == 0, "replayed=" + n);
    }

    /**
     * seek 越界必须钳到时间线范围内。
     *
     * <p>修复前 currentTick = max(0, tick) 不看上限，seek 到末尾之后
     * 时钟停在一个不属于任何事件的时间点上——进度条算出的百分比
     * 与实际重放位置对不上。</p>
     */
    static void replaySeekIsClampedToTimeline() {
        ReplayEngine r = new ReplayEngine(events(100, 200, 300));
        r.seek(9999);
        check("seek越界被钳到末尾", r.getCurrentTick() == 300, "tick=" + r.getCurrentTick());
        r.seek(-50);
        check("seek负值被钳到起点", r.getCurrentTick() == 100, "tick=" + r.getCurrentTick());
        r.seek(150);
        check("seek中段生效", r.getCurrentTick() == 150, "tick=" + r.getCurrentTick());
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

    /**
     * 坐标必须是有限值：NaN / Infinity 判定为无效事件。
     *
     * <p>NaN 坐标会让客户端矩阵变换整体变成 NaN，结果是一整片区块不渲染，
     * 且很难查。.wrp 可以是手工构造的，不能假设坐标合理。</p>
     */
    static void eventRejectsNonFiniteCoords() {
        check("正常坐标有效", new Event(1, "t", "p", "d", 0, 0, 0, "{}").isValid());
        check("NaN x 无效",
                !new Event(1, "t", "p", "d", Double.NaN, 0, 0, "{}").isValid());
        check("NaN y 无效",
                !new Event(1, "t", "p", "d", 0, Double.NaN, 0, "{}").isValid());
        check("NaN z 无效",
                !new Event(1, "t", "p", "d", 0, 0, Double.NaN, "{}").isValid());
        check("Infinity x 无效",
                !new Event(1, "t", "p", "d", Double.POSITIVE_INFINITY, 0, 0, "{}").isValid());
        check("-Infinity z 无效",
                !new Event(1, "t", "p", "d", 0, 0, Double.NEGATIVE_INFINITY, "{}").isValid());
        // 负坐标是合法的（世界有负坐标区域），不能误杀
        check("负坐标有效", new Event(1, "t", "p", "d", -100, -64, -200, "{}").isValid());
    }

    /**
     * 并发 append 与 export 不抛 ConcurrentModificationException。
     *
     * <p>Fabric 的 DISCONNECT 回调可能落在网络线程，与主线程的 append
     * 同时发生。旧实现用裸 ArrayList，导出时会撞上正在被修改的列表。</p>
     */
    static void sessionConcurrentAppendAndExport() throws Exception {
        RecordingSession s = RecordingSession.start(0, "concurrent");
        int n = 400;
        Thread writer = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                // 必须 >= 0 且坐标有限，否则被 append 拒掉（不影响本测试目的）
                s.append(new Event(i % 50, "e", "p", "d", i, 0, 0, "{}"));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try {
                    s.export();
                } catch (java.util.ConcurrentModificationException cme) {
                    throw new RuntimeException("并发导出撞上修改", cme);
                } catch (java.io.IOException ignored) {
                }
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        check("并发 append/export 无异常", true);
        check("并发后事件数正确", s.eventCount() == n, "got=" + s.eventCount());
    }

    /** 解码器跳过无效事件（tick<0 / type 空）返回 null。 */
    static void decoderSkipsInvalid() throws IOException {
        Event bad = new Event(-5, "ok", "p", "d", 0, 0, 0, "{}");
        check("负tick encode→decode为null", EventDecoder.decode(EventEncoder.encode(bad)) == null);
        Event bad2 = new Event(5, "", "p", "d", 0, 0, 0, "{}");
        check("空type encode→decode为null", EventDecoder.decode(EventEncoder.encode(bad2)) == null);
    }

    // ---- EventCounter ----

    /**
     * 事件数统计：正常计数、缓存命中、文件改动后重算、损坏文件返回 0。
     *
     * <p>缓存是这个类的全部意义所在——旧实现每次开 GUI 都要把每个录制
     * 文件完整读一遍，所以这里要验证"文件没动就不重扫"。</p>
     */
    static void eventCounterCountsAndCaches() throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wr-counter");
        try {
            java.nio.file.Path f = dir.resolve("a.wrp");
            java.nio.file.Files.write(f, sessionBytes(3));
            EventCounter.clearCache();
            check("计数=3", EventCounter.countEvents(f) == 3, "got=" + EventCounter.countEvents(f));
            check("缓存命中结果一致", EventCounter.countEvents(f) == 3);

            // 改文件（内容变了 → 大小/mtime 变化）后应重算
            java.nio.file.Files.write(f, sessionBytes(5));
            EventCounter.clearCache();
            check("改文件后重算=5", EventCounter.countEvents(f) == 5, "got=" + EventCounter.countEvents(f));

            // 损坏文件：magic 不符 → 返回 0，不抛异常
            java.nio.file.Path bad = dir.resolve("bad.wrp");
            java.nio.file.Files.write(bad, new byte[]{0x01, 0x02, 0x03, 0x04, 0x05});
            check("损坏文件返回0", EventCounter.countEvents(bad) == 0);

            // 截断文件：头正常但事件不完整 → 返回 0（不当成损坏算出天文数字）
            java.nio.file.Path trunc = dir.resolve("t.wrp");
            byte[] full = sessionBytes(4);
            java.nio.file.Files.write(trunc, java.util.Arrays.copyOf(full, full.length - 6));
            check("截断文件返回0", EventCounter.countEvents(trunc) == 0,
                    "got=" + EventCounter.countEvents(trunc));

            // 不存在的文件 → 0，不抛异常
            check("不存在文件返回0", EventCounter.countEvents(dir.resolve("nope.wrp")) == 0);
        } finally {
            try (var s = java.nio.file.Files.list(dir)) {
                for (var p : (Iterable<java.nio.file.Path>) s::iterator) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            }
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    /** 造一个含 n 条事件的会话文件字节流（头 + n 条事件）。 */
    static byte[] sessionBytes(int n) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        EventEncoder.writeHeader(out);
        for (int i = 0; i < n; i++) {
            EventEncoder.writeTo(new Event(i, "block_break", "p1",
                    "minecraft:overworld", i, 0, 0, "{}"), out);
        }
        out.flush();
        return bos.toByteArray();
    }

    /**
     * EventCounter 的计数必须与 EventDecoder 实际能解出的条数一致。
     *
     * <p>不一致的话，GUI 上显示的事件数就和实际能回放的数量对不上——
     * 用户会以为录了 100 条结果只播了 98 条。</p>
     */
    static void eventCounterMatchesDecoder() throws IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wr-counter2");
        try {
            java.nio.file.Path f = dir.resolve("mixed.wrp");
            // 3 条有效 + 2 条无效（NaN 坐标、负 tick）
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
            EventEncoder.writeHeader(out);
            for (int i = 0; i < 3; i++) {
                EventEncoder.writeTo(new Event(i, "block_break", "p1",
                        "minecraft:overworld", i, 0, 0, "{}"), out);
            }
            EventEncoder.writeTo(new Event(10, "bad", "p1",
                    "minecraft:overworld", Double.NaN, 0, 0, "{}"), out);
            EventEncoder.writeTo(new Event(-1, "bad", "p1",
                    "minecraft:overworld", 0, 0, 0, "{}"), out);
            out.flush();
            java.nio.file.Files.write(f, bos.toByteArray());

            // 解码器实际能解出的条数
            int decoded = 0;
            try (java.io.DataInputStream in = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(bos.toByteArray()))) {
                EventDecoder.readHeader(in);
                while (in.available() > 0) {
                    Event ev = EventDecoder.readFrom(in);
                    if (ev != null) decoded++;
                }
            }
            check("解码器解出3条", decoded == 3, "got=" + decoded);

            EventCounter.clearCache();
            int counted = EventCounter.countEvents(f);
            check("计数与解码一致", counted == decoded,
                    "counted=" + counted + " decoded=" + decoded);
        } finally {
            try (var s = java.nio.file.Files.list(dir)) {
                for (var p : (Iterable<java.nio.file.Path>) s::iterator) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            }
            java.nio.file.Files.deleteIfExists(dir);
        }
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
