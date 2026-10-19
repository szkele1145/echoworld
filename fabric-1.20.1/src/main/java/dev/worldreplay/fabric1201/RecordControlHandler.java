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
 */
public final class RecordControlHandler {

    /** 录制目录相对游戏目录的子路径。 */
    private static final String RECORDINGS_DIR_NAME = "worldreplay/recordings";
    /** 临时文件后缀（重命名原子操作用）。 */
    public static final String WR_RENAME_TMP = ".wrp.tmp";

    private RecordControlHandler() {}

    /** 录制目录：{@code <gameDir>/worldreplay/recordings}。 */
    public static Path recordingsDir() {
        return FabricLoader.getInstance().getGameDir().resolve(RECORDINGS_DIR_NAME);
    }

    /** 防目录穿越：只允许文件名，不含路径分隔符与 .. 等。 */
    public static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        if (name.contains("/") || name.contains("\\")) return false;
        if (name.equals(".") || name.equals("..")) return false;
        if (name.contains("\0")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20) return false;
        }
        return true;
    }

    /** 是否以 .wrp 结尾（不区分大小写）。 */
    public static boolean hasWrpSuffix(String name) {
        return name != null && name.toLowerCase().endsWith(".wrp");
    }

    /** 扫描 recordings 目录并把文件列表推给玩家（附 OpenRecordLibrary 信号）。 */
    public static void openRecordLibrary(ServerPlayerEntity player) {
        Path dir = recordingsDir();
        List<RecordListPackets.RecordListEntry> entries = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    String name = f.getFileName().toString();
                    if (!hasWrpSuffix(name) || !Files.isRegularFile(f)) continue;
                    try {
                        entries.add(new RecordListPackets.RecordListEntry(
                                name, Files.size(f), Files.getLastModifiedTime(f).toMillis()));
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        WorldReplayNetwork.sendRecordList(player, entries);
        WorldReplayNetwork.sendOpenRecordLibrary(player);
    }

    /** 把录制文件数据分片下发给玩家，返回发送的分片数。 */
    public static int sendRecording(ServerPlayerEntity player, String fileName, byte[] data) {
        return WorldReplayNetwork.sendRecording(player, fileName, data);
    }

    /** 删除录制文件（服务端执行，返回成功与否给客户端）。 */
    public static void deleteRecording(ServerPlayerEntity player, String fileName) {
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
        if (!WorldReplayConfig.RENAME_ENABLED) {
            notifyFailure(player, "重命名功能已禁用");
            return;
        }
        if (!isSafeFileName(oldName) || !isSafeFileName(newName)) {
            notifyFailure(player, "非法文件名");
            return;
        }
        Path dir = recordingsDir();
        Path src = dir.resolve(hasWrpSuffix(oldName) ? oldName : oldName + ".wrp");
        Path dst = dir.resolve(hasWrpSuffix(newName) ? newName : newName + ".wrp");
        if (!Files.isRegularFile(src)) {
            notifyFailure(player, "源文件不存在: " + oldName);
            return;
        }
        if (Files.exists(dst)) {
            notifyFailure(player, "目标文件已存在: " + newName);
            return;
        }
        try {
            Files.move(src, dst);
            openRecordLibrary(player);
        } catch (IOException e) {
            notifyFailure(player, "重命名失败: " + e.getMessage());
        }
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

    /** 净化显示名：去除非法字符，限制长度。 */
    public static String sanitizeDisplayName(String name) {
        if (name == null) return "unnamed";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.length() > 96) s = s.substring(0, 96);
        return s.isEmpty() ? "unnamed" : s;
    }
}
