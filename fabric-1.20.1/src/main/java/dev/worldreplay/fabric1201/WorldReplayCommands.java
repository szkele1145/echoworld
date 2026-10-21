package dev.worldreplay.fabric1201;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldreplay.core.event.EventDecoder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.util.List;

/**
 * /wr 命令：录制库与回放入口。
 *
 * <p>子命令：
 * <ul>
 *   <li>/wr gui —— 服务端扫描 recordings 目录并把文件列表推给执行者，
 *       客户端收到 OpenRecordLibrary + RecordList 后打开录制库</li>
 *   <li>/wr &lt;file&gt; —— 服务端加载校验并把录制分片推给执行者，
 *       客户端 ReplayVisualizer 直接开始回放</li>
 * </ul></p>
 */
public final class WorldReplayCommands {

    /** 注册到服务端命令树（Fabric 1.20.1 新命令 API）。 */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wr")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("gui").executes(ctx -> {
                    ServerCommandSource source = ctx.getSource();
                    if (source.getPlayer() != null) {
                        RecordControlHandler.openRecordLibrary(source.getPlayer());
                    }
                    return 1;
                }))
                .then(CommandManager.argument("file", StringArgumentType.string())
                        .executes(ctx -> replay(ctx.getSource(), StringArgumentType.getString(ctx, "file")))));
    }

    private static int replay(ServerCommandSource src, String file) {
        if (!RecordControlHandler.isSafeFileName(file)) {
            src.sendFeedback(() -> Text.literal("[WorldReplay] Invalid file name"), false);
            return 0;
        }
        java.nio.file.Path dir = RecordControlHandler.recordingsDir();
        java.nio.file.Path f = dir.resolve(RecordControlHandler.hasWrpSuffix(file) ? file : file + ".wrp");
        if (!Files.isRegularFile(f)) {
            src.sendFeedback(() -> Text.literal("[WorldReplay] Not found: " + f.getFileName()), false);
            return 0;
        }
        try {
            // 与 nf 1.21.1 同一条上限：超大录制先挡住，
            // 别 readAllBytes 进内存再被客户端丢弃
            long size = Files.size(f);
            if (size > RecordControlHandler.MAX_SEND_BYTES) {
                src.sendFeedback(() -> Text.literal("[WorldReplay] Recording too large to replay: "
                        + f.getFileName() + " (" + (size / (1024 * 1024)) + " MiB)"), false);
                return 0;
            }
            // 校验头 + 数事件数：用 EventCounter 流式扫描，不把每个事件
            // 构造出来装进 List——480 MiB 的录制能有上千万条事件，
            // 全装进内存就是 GB 级，而这里只需要一个条数。
            try (java.io.DataInputStream hin = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(f)))) {
                byte ver = EventDecoder.readHeader(hin);
                if (ver <= 0) {
                    src.sendFeedback(() -> Text.literal("[WorldReplay] Bad magic (not an WorldReplay recording)"), false);
                    return 0;
                }
            }
            int count = dev.worldreplay.core.event.EventCounter.countEvents(f);
            if (count <= 0) {
                src.sendFeedback(() -> Text.literal("[WorldReplay] Nothing to replay: " + f.getFileName()
                        + " has no readable events"), false);
                return 0;
            }
            byte[] data = Files.readAllBytes(f);
            if (src.getPlayer() == null) {
                src.sendFeedback(() -> Text.literal("[WorldReplay] Replay needs a player (run it in-game)"), false);
                return 0;
            }
            int chunks = RecordControlHandler.sendRecording(src.getPlayer(), f.getFileName().toString(), data);
            final String msg = "[WorldReplay] Replaying " + count + " events from "
                    + f.getFileName() + " (" + chunks + " chunks)";
            src.sendFeedback(() -> Text.literal(msg), false);
            return count;
        } catch (Exception ex) {
            src.sendFeedback(() -> Text.literal("[WorldReplay] Load failed: " + ex.getMessage()), false);
            return 0;
        }
    }

    private WorldReplayCommands() {}
}
