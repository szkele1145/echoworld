package dev.worldreplay.core.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 回放引擎：按 tick 升序重放事件序列。
 *
 * <p>非线程安全，假定在单一驱动线程（游戏 tick 循环）上推进。
 * speed 支持 0.25x ~ 64x 倍速，超出范围的设置会被钳制。</p>
 */
public class ReplayEngine {

    /** 允许的最小/最大倍速。 */
    public static final double MIN_SPEED = 0.25;
    public static final double MAX_SPEED = 64.0;

    private final List<Event> timeline = new ArrayList<>();
    private int cursor = 0;                 // 下一条待重放事件在 timeline 中的下标
    private long currentTick = 0;
    private double speed = 1.0;
    private boolean playing = false;
    private Event currentEvent = null;
    /** 慢速小数累积器：speed<1 时跨 tick 累积，满 1 才推进（修 0.25x/0.5x 失效）。 */
    private double tickAccum = 0.0;

    /** 有序时间线视图（渲染用，与内部同序）。 */
    public List<Event> timelineView() {
        return java.util.Collections.unmodifiableList(timeline);
    }

    public ReplayEngine(List<Event> events) {
        if (events != null) {
            for (Event e : events) {
                if (e != null && e.isValid()) {
                    timeline.add(e);
                }
            }
            timeline.sort(Comparator.comparingLong(Event::tick));
        }
        // 归一到首事件 tick：gameTime 大的录制不从 0 空转（与 stop() 语义一致）
        currentTick = timeline.isEmpty() ? 0 : timeline.get(0).tick();
        tickAccum = 0.0;
    }

    /** 开始/恢复回放。 */
    public void play() {
        if (cursor < timeline.size()) {
            playing = true;
        }
    }

    /** 暂停（保留进度）。 */
    public void pause() {
        playing = false;
    }

    /** 停止并回到起点。 */
    public void stop() {
        playing = false;
        cursor = 0;
        currentTick = timeline.isEmpty() ? 0 : timeline.get(0).tick();
        tickAccum = 0.0;
        currentEvent = null;
    }

    /**
     * 每个 tick 驱动一次：推进内部时钟（受 speed 影响），
     * 重放所有 tick <= currentTick 的事件。
     *
     * @return 本次驱动实际重放的事件数
     */
    public int tick() {
        if (!playing) {
            return 0;
        }
        long prev = currentTick;
        // 小数累积推进：0.25x → 每 4 tick 前进 1；0.5x → 每 2 tick 前进 1（修慢速失效）
        tickAccum += speed;
        long adv = (long) tickAccum;
        if (adv > 0) {
            tickAccum -= adv;
            currentTick += adv;
        }
        if (currentTick == prev) {
            return 0;   // 慢速累积不足 1：本 tick 不推进（不再强制 +1 破坏慢速语义）
        }
        return advanceTo(currentTick);
    }

    /** 跳转到指定 tick：游标二分定位，重放区间的最后一条事件成为 currentEvent。 */
    public void seek(long tick) {
        currentTick = Math.max(0, tick);
        cursor = findFirstIndexAtOrAfter(currentTick);
        currentEvent = cursor > 0 ? timeline.get(cursor - 1) : null;
    }

    /** 推进到目标 tick，重放 (上一 currentTick, target] 区间内的事件。 */
    private int advanceTo(long targetTick) {
        int replayed = 0;
        while (cursor < timeline.size() && timeline.get(cursor).tick() <= targetTick) {
            currentEvent = timeline.get(cursor);
            cursor++;
            replayed++;
        }
        if (cursor >= timeline.size()) {
            playing = false; // 播放到末尾自动停止
        }
        return replayed;
    }

    /** 二分查找第一个 tick >= target 的下标。 */
    private int findFirstIndexAtOrAfter(long target) {
        int lo = 0, hi = timeline.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (timeline.get(mid).tick() < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** 当前事件（最近重放的一条），无则为 null。 */
    public Event getCurrentEvent() {
        return currentEvent;
    }

    /** 是否正在播放。 */
    public boolean isPlaying() {
        return playing;
    }

    /** 当前内部 tick。 */
    public long getCurrentTick() {
        return currentTick;
    }

    /** 总事件数。 */
    public int totalEvents() {
        return timeline.size();
    }

    /** 时间线上最后一个事件的 tick（空时间线为 0）。 */
    public long endTick() {
        return timeline.isEmpty() ? 0 : timeline.get(timeline.size() - 1).tick();
    }

    /** 当前倍速。 */
    public double getSpeed() {
        return speed;
    }

    /** 设置倍速，钳制到 [0.25, 64]。 */
    public void setSpeed(double speed) {
        if (Double.isNaN(speed) || speed < MIN_SPEED) {
            this.speed = MIN_SPEED;
        } else if (speed > MAX_SPEED) {
            this.speed = MAX_SPEED;
        } else {
            this.speed = speed;
        }
    }
}
