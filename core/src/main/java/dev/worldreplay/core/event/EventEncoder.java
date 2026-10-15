package dev.worldreplay.core.event;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Event 的二进制编码器。
 *
 * <p>格式（大端）：</p>
 * <pre>
 * [magic: short 0xE7C0] [formatVersion: byte = 1]
 * [tick: long] [type: UTF] [playerId: UTF] [dimension: UTF]
 * [x: double] [y: double] [z: double] [data: UTF]
 * </pre>
 *
 * <p>可扩展性：新增字段一律追加到末尾并提升 formatVersion；
 * 旧 reader 读到更高版本时只解析自己认识的 v1 前缀，向前兼容。</p>
 */
public final class EventEncoder {

    /** 魔数，用于快速识别损坏数据。 */
    public static final short MAGIC = (short) 0xE7C0;
    /** 当前格式版本。 */
    public static final byte FORMAT_VERSION = 1;

    private EventEncoder() {
    }

    /** 将单个事件编码为字节数组。 */
    public static byte[] encode(Event event) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        DataOutputStream out = new DataOutputStream(bos);
        writeTo(event, out);
        out.flush();
        return bos.toByteArray();
    }

    /** 将单个事件写入已有的 DataOutputStream（不写 magic/version 头，供会话流式追加）。 */
    public static void writeTo(Event event, DataOutputStream out) throws IOException {
        out.writeLong(event.tick());
        writeUtf(out, event.type());
        writeUtf(out, event.playerId());
        writeUtf(out, event.dimension());
        out.writeDouble(event.x());
        out.writeDouble(event.y());
        out.writeDouble(event.z());
        writeUtf(out, event.data());
    }

    /** 写入会话文件头：magic + formatVersion。 */
    public static void writeHeader(DataOutputStream out) throws IOException {
        out.writeShort(MAGIC);
        out.writeByte(FORMAT_VERSION);
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        // null -> ""；超长防御：writeUTF 上限 65535 字节，超限时截断并尾标记（编码永不抛异常）
        String v = value == null ? "" : value;
        byte[] bytes = v.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65000) {
            // 回退到 UTF-8 字符边界（不切开多字节字符），再补截断标记
            int end = 65000;
            while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;   // 跳过 10xxxxxx 后缀字节
            v = new String(bytes, 0, end, StandardCharsets.UTF_8) + "…[truncated]";
        }
        out.writeUTF(v);
    }

    /** 便于调试的十六进制表示。 */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
