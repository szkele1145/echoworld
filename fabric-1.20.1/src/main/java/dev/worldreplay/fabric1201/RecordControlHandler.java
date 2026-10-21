package dev.worldreplay.fabric1201;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WorldReplay Fabric 1.20.1 录制目录与文件控制（落盘路径统一入口，避免三处字面量漂移）。
 *
 * <p>录制目录统一为 {@code <gameDir>/worldreplay/recordings}，
 * 通过 {@link FabricLoader#getGameDir()} 定位，服务端与客户端通用。</p>
 *
 * <p><b>v0.2.3 修复（对齐 NeoForge 侧 v0.2.0 的三处缺陷）</b>：
 * <ol>
 *   <li>大小写不敏感文件系统上，"仅改大小写"的改名曾被 {@code Files.exists}
 *       误判为同名冲突，用户被迫换个名字；</li>
 *   <li>NTFS 上直接 {@code move} 到仅大小写不同的目标不会真正改名
 *       （实测磁盘条目仍是原名），改为"临时名 + 目标名"两步移动；</li>
 *   <li>目标已存在时旧实现直接报错拒绝，用户得自己想名字；
 *       现在自动追加 {@code -2}、{@code -3}… 递增，绝不覆盖既有录制。</li>
 * </ol>
 * 另修：{@code sanitizeDisplayName} 不剥离大小写不敏感的 {@code .wrp} 后缀
 * （"Kept.WRP" 曾落盘成 "Kept.WRP.wrp"）；{@code isSafeFileName} 放行了
 * Windows 保留字符（{@code : * ? " < > |}）。</p>
 */
public final class RecordControlHandler {

    /** 录制目录相对游戏目录的子路径。 */
    private static final String RECORDINGS_DIR_NAME = "worldreplay/recordings";
    /** 临时文件后缀（重命名原子操作用）。 */
    public static final String WR_RENAME_TMP = ".wrp.tmp";

    /**
     * 单个录制允许下发的最大字节数（480 MiB），与客户端
     * {@code ReplayVisualizer.MAX_TOTAL_BYTES} 一致。
     *
     * <p>下发路径是 {@code Files.readAllBytes} + 逐片 {@code copyOfRange}，
     * 峰值内存约为文件大小的两倍。</p>
     */
    public static final long MAX_SEND_BYTES = 480L * 1024 * 1024;

    private RecordControlHandler() {}

    /** 录制目录：{@code <gameDir>/worldreplay/recordings}。 */
    public static Path recordingsDir() {
        return FabricLoader.getInstance().getGameDir().resolve(RECORDINGS_DIR_NAME);
    }

    /**
     * 防目录穿越：只允许文件名，不含路径分隔符与 .. 等。
     *
     * <p>Windows 保留字符（{@code : * ? " < > |}）一并拒绝——
     * 客户端输入框虽然挡了这些字符，但包可以手工构造，不能只靠客户端。</p>
     */
    public static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        if (name.contains("/") || name.contains("\\")) return false;
        if (name.equals(".") || name.equals("..")) return false;
        if (name.contains("..")) return false;   // 任何位置的 ".." 都拒绝
        if (name.contains("\0")) return false;
        if (name.contains(":") || name.contains("*") || name.contains("?")
                || name.contains("\"") || name.contains("<") || name.contains(">")
                || name.contains("|")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;   // 控制字符
        }
        return true;
    }

    /** 是否以 .wrp 结尾（不区分大小写）。 */
    public static boolean hasWrpSuffix(String name) {
        return name != null && name.length() > 4
                && name.regionMatches(true, name.length() - 4, ".wrp", 0, 4);
    }

    /** 扫描 recordings 目录并把文件列表推给玩家（附 OpenRecordLibrary 信号）。 */
    public static void openRecordLibrary(ServerPlayerEntity player) {
        // 纵深防御：列表包含所有玩家的行为记录，只发给 OP
        if (!player.hasPermissionLevel(2)) return;
        List<RecordListPackets.RecordListEntry> entries = new ArrayList<>();
        Path dir = recordingsDir();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    String name = f.getFileName().toString();
                    if (!hasWrpSuffix(name) || !Files.isRegularFile(f)) continue;
                    try {
                        entries.add(new RecordListPackets.RecordListEntry(
                                name, Files.size(f), Files.getLastModifiedTime(f).toMillis(),
                                dev.worldreplay.core.event.EventCounter.countEvents(f)));
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        // 顺序不可颠倒：客户端以 open_record_library 置位"开新屏"，
        // 随后的 record_list 才据此构建界面
        WorldReplayNetwork.sendOpenRecordLibrary(player);
        WorldReplayNetwork.sendRecordList(player, entries);
    }

    /** 把录制文件数据分片下发给玩家，返回发送的分片数。 */
    public static int sendRecording(ServerPlayerEntity player, String fileName, byte[] data) {
        return WorldReplayNetwork.sendRecording(player, fileName, data);
    }

    /** 删除录制文件（服务端执行，返回成功与否给客户端）。 */
    public static void deleteRecording(ServerPlayerEntity player, String fileName) {
        // 纵深防御：网络层已校验 OP，这里再挡一次——
        // 这两个方法是 public static，可能被别的调用路径直接调到。
        if (!player.hasPermissionLevel(2)) return;
        if (!isSafeFileName(fileName)) {
            notifyFailure(player, "非法文件名");
            return;
        }
        Path dir = recordingsDir();
        Path f = dir.resolve(hasWrpSuffix(fileName) ? fileName : fileName + ".wrp");
        if (!Files.isRegularFile(f)) {
            notifyFailure(player, "文件不存在: " + fileName);
            return;
        }
        try {
            Files.deleteIfExists(f);
            openRecordLibrary(player);
        } catch (IOException e) {
            notifyFailure(player, "删除失败: " + e.getMessage());
        }
    }

    /** 重命名录制文件（受 WorldReplayConfig.RENAME_ENABLED 开关控制）。 */
    public static void renameRecording(ServerPlayerEntity player, String oldName, String newName) {
        // 纵深防御：网络层已校验 OP，这里再挡一次
        if (!player.hasPermissionLevel(2)) return;
        if (!WorldReplayConfig.RENAME_ENABLED) {
            notifyFailure(player, "重命名功能已禁用");
            return;
        }
        if (!isSafeFileName(oldName)) {
            notifyFailure(player, "非法文件名");
            return;
        }
        String cleaned = sanitizeDisplayName(newName);
        if (cleaned.isEmpty()) {
            notifyFailure(player, "新名字无效");
            return;
        }
        Path dir = recordingsDir();
        Path src = dir.resolve(hasWrpSuffix(oldName) ? oldName : oldName + ".wrp");
        if (!Files.isRegularFile(src)) {
            notifyFailure(player, "源文件不存在: " + oldName);
            return;
        }
        Path dst = freeTarget(dir, src, cleaned);
        if (dst == null) {
            notifyFailure(player, "可用文件名已耗尽");
            return;
        }
        if (dst.getFileName().toString().equals(src.getFileName().toString())) {
            openRecordLibrary(player);   // 名字与磁盘上的完全一致：免 IO
            return;
        }
        try {
            if (dst.getFileName().toString().equalsIgnoreCase(src.getFileName().toString())) {
                // 仅大小写不同：NTFS 直接 move 不会改变磁盘上的条目名（实测），
                // 必须先经一个不冲突的临时名，再由临时名挪到目标名。
                Path tmp = dir.resolve(WR_RENAME_TMP);
                Files.deleteIfExists(tmp);
                Files.move(src, tmp);
                Files.move(tmp, dst);
            } else {
                Files.move(src, dst);   // 同目录移动：原子性足够
            }
            openRecordLibrary(player);
        } catch (Exception e) {
            notifyFailure(player, "重命名失败: " + e.getMessage());
            try {
                Files.deleteIfExists(dir.resolve(WR_RENAME_TMP));   // 清理可能残留的中间名
            } catch (IOException ignored) {}
        }
    }

    /**
     * 求一个不冲突的目标路径；可用名字耗尽时返回 {@code null}。
     *
     * <p>关键差异：{@code Files.exists(dst)} 在大小写不敏感的文件系统上，
     * 对"仅大小写不同的源文件自身"同样返回 true。这里用
     * {@link Files#isSameFile} 排除"目标即源文件自身"。</p>
     */
    private static Path freeTarget(Path dir, Path src, String cleaned) {
        for (int n = 1; n < 1000; n++) {
            Path dst = dir.resolve(n == 1 ? cleaned + ".wrp" : cleaned + "-" + n + ".wrp");
            if (!dst.normalize().getParent().equals(dir.normalize())) return null;   // 双保险：不越界
            if (!Files.exists(dst)) return dst;
            try {
                if (Files.isSameFile(src, dst)) return dst;   // 目标就是自己（仅大小写不同）
            } catch (IOException ignored) {
                // 无法判定同一性：保守视为冲突，继续尝试下一个序号
            }
        }
        return null;   // 1..999 全部被占用
    }

    /** 向客户端报告操作失败（文本提示）。 */
    public static void notifyFailure(ServerPlayerEntity player, String message) {
        player.sendMessage(net.minecraft.text.Text.literal("[WorldReplay] " + message), false);
    }

    /** 释放目标文件（删除临时 .wrp.tmp 若存在）。 */
    public static void freeTarget(Path file) {
        if (file != null) {
            Path tmp = file.resolveSibling(file.getFileName() + WR_RENAME_TMP);
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    /**
     * 净化显示名：去非法字符、折叠空白、限长（空串=无效）。
     *
     * <p>大小写不敏感地剥掉 {@code .wrp} 后缀——后缀由服务端统一补，
     * 否则 "Kept.WRP" 会落盘成 "Kept.WRP.wrp"。</p>
     */
    public static String sanitizeDisplayName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < 96; i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;                        // 控制字符
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') continue;   // 路径/保留字符
            sb.append(c);
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);     // Windows 不允许尾点
        if (s.length() >= 4 && s.regionMatches(true, s.length() - 4, ".wrp", 0, 4)) {
            s = s.substring(0, s.length() - 4);
            while (s.endsWith(".")) s = s.substring(0, s.length() - 1);   // 剥离后可能又露出尾点
        }
        s = s.trim();
        if (s.length() > 96) s = s.substring(0, 96);
        return s;
    }
}
