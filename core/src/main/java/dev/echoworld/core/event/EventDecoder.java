package dev.echoworld.core.event;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Event 的二进制解码器，与 {@link EventEncoder} 对应。
 *
 * <p>校验规则：tick < 0 或 type 为空的事件视为无效，直接跳过（返回 null），
 * 不抛异常——录制文件可能来自旧版本或被截断，回放应尽力而为。</p>
 */
public final class EventDecoder {

    private EventDecoder() {
    }

    /**
     * 从字节数组解码单个事件（含 magic/version 头）。
     *
     * @return 解码结果；无效事件返回 null
     * @throws IOException 数据损坏（magic 不符、流截断等）
     */
    public static Event decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        return readFrom(in);
    }

    /**
     * 从 DataInputStream 读取单个事件体（不含 magic/version 头）。
     *
     * @return 解码结果；事件无效（tick<0 或 type 空）返回 null
     * @throws IOException 流结束或损坏
     */
    public static Event readFrom(DataInputStream in) throws IOException {
        long tick = in.readLong();
        String type = readUtf(in);
        String playerId = readUtf(in);
        String dimension = readUtf(in);
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        String data = readUtf(in);

        Event event = new Event(tick, type, playerId, dimension, x, y, z, data);
        return event.isValid() ? event : null;
    }

    /**
     * 读取并校验会话文件头。
     *
     * @return formatVersion
     * @throws IOException magic 不符或流损坏
     */
    public static byte readHeader(DataInputStream in) throws IOException {
        short magic = in.readShort();
        if (magic != EventEncoder.MAGIC) {
            throw new IOException("Bad magic: 0x" + Integer.toHexString(magic & 0xFFFF));
        }
        return in.readByte();
    }

    private static String readUtf(DataInputStream in) throws IOException {
        String value = in.readUTF();
        // encoder 将 null 编码为 ""，解码时还原为 null
        return value.isEmpty() ? null : value;
    }
}
