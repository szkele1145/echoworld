package dev.worldreplay.core.event;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * .wrp 录制文件的<b>事件数</b>统计。
 *
 * <p>文件格式是"头 + 逐条事件流"，没有记录总条数（见 {@link EventEncoder}），
 * 所以只能顺序扫一遍才能数出来。GUI 的录制列表要显示每条录制的事件数，
 * 于是这个扫描就落在了"每次开列表都跑一遍"的路径上——
 * 录制多了就是每次开 GUI 重新读一遍全部录制文件。</p>
 *
 * <p>本类用 <b>(路径, 大小, 修改时间)</b> 做缓存键：文件没被改动就直接返回
 * 上次的计数，不再碰磁盘。文件被改动（大小或 mtime 变化）则自动重算。
 * 缓存条目数有上限，长时间运行不会无限增长。</p>
 *
 * <p>扫描只逐字段跳过（读长度前缀后跳过字节），不解码事件内容，
 * 也不构造 {@link Event} 对象——比真正解码快一个量级。</p>
 */
public final class EventCounter {

    /** 缓存条目上限：超出后整体清空重来（防止长期运行积累过多）。 */
    private static final int MAX_CACHE_ENTRIES = 4096;

    /** key = 绝对路径；value = {size, modified, count}。 */
    private static final Map<String, long[]> CACHE = new ConcurrentHashMap<>();

    private EventCounter() {
    }

    /**
     * 统计 .wrp 文件里的事件数。
     *
     * <p>文件损坏或被截断时返回 0（与旧实现一致），并清掉该文件的缓存条目，
     * 免得坏文件的结果被缓存住。</p>
     */
    public static int countEvents(Path file) {
        try {
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            String key = file.toAbsolutePath().toString();
            long[] cached = CACHE.get(key);
            if (cached != null && cached[0] == size && cached[1] == modified) {
                return (int) cached[2];
            }
            int count = scan(file);
            if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(key, new long[]{size, modified, count});
            return count;
        } catch (IOException e) {
            // 文件可能刚被删除（删除操作与列表刷新之间）
            CACHE.remove(file.toAbsolutePath().toString());
            return 0;
        }
    }

    /** 丢弃某个文件的缓存（文件被改写/删除后调用；一般不需要主动调）。 */
    public static void invalidate(Path file) {
        CACHE.remove(file.toAbsolutePath().toString());
    }

    /** 清空全部缓存（测试用）。 */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 顺序扫一遍文件，数出事件条数。
     *
     * <p>遇到截断/损坏直接抛 {@link IOException} 由上层处理——不能"尽力数到
     * 文件末尾"：那会把错位读到的字节解释成天文数字的事件数。</p>
     */
    private static int scan(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (EventDecoder.readHeader(in) <= 0) {
                return 0;
            }
            int count = 0;
            while (in.available() > 0) {
                in.readLong();              // tick
                skipUtf(in);                 // type
                skipUtf(in);                 // playerId
                skipUtf(in);                 // dimension
                in.readDouble();             // x
                in.readDouble();             // y
                in.readDouble();             // z
                skipUtf(in);                 // data
                count++;
            }
            return count;
        }
    }

    /**
     * 跳过一段 UTF 字段。
     *
     * <p>{@code DataInputStream.skipBytes} 内部会循环到底，正常情况返回值
     * 等于请求长度；字节流被截断时不足，此时必须报错交给上层按损坏处理，
     * 而不是让后续 {@code readLong}/{@code readDouble} 读到错位数据。</p>
     */
    private static void skipUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        int skipped = in.skipBytes(len);
        if (skipped != len) {
            throw new EOFException("truncated string field");
        }
    }
}
