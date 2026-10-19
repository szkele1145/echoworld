package dev.worldreplay.fabric1194;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldreplay.core.event.EventDecoder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

    /** 注册到服务端命令树（Fabric 1.19.4 新命令 API）。 */
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
            src.sendFeedback(Text.literal("[WorldReplay] Invalid file name"), false);
            return 0;
        }
        java.nio.file.Path dir = RecordControlHandler.recordingsDir();
        java.nio.file.Path f = dir.resolve(RecordControlHandler.hasWrpSuffix(file) ? file : file + ".wrp");
        if (!Files.isRegularFile(f)) {
            src.sendFeedback(Text.literal("[WorldReplay] Not found: " + f.getFileName()), false);
            return 0;
        }
        try {
            byte[] data = Files.readAllBytes(f);
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            byte ver = EventDecoder.readHeader(in);
            if (ver <= 0) {
                src.sendFeedback(Text.literal("[WorldReplay] Bad magic (not an WorldReplay recording)"), false);
                return 0;
            }
            java.util.List<dev.worldreplay.core.event.Event> events = new java.util.ArrayList<>();
            int bad = 0;
            while (in.available() > 0) {
                dev.worldreplay.core.event.Event ev = EventDecoder.readFrom(in);
                if (ev != null) events.add(ev);
                else bad++;
            }
            if (events.isEmpty()) {
                src.sendFeedback(Text.literal("[WorldReplay] Nothing to replay: " + f.getFileName()
                        + " has no readable events"), false);
                return 0;
            }
            if (src.getPlayer() == null) {
                src.sendFeedback(Text.literal("[WorldReplay] Replay needs a player (run it in-game)"), false);
                return 0;
            }
            int chunks = RecordControlHandler.sendRecording(src.getPlayer(), f.getFileName().toString(), data);
            final String msg = "[WorldReplay] Replaying " + events.size() + " events ("
                    + bad + " skipped) from " + f.getFileName() + " (" + chunks + " chunks)";
            src.sendFeedback(Text.literal(msg), false);
            return events.size();
        } catch (Exception ex) {
            src.sendFeedback(Text.literal("[WorldReplay] Load failed: " + ex.getMessage()), false);
            return 0;
        }
    }

    private WorldReplayCommands() {}
}
