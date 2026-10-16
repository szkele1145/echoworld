package dev.worldreplay.nf1211;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldreplay.core.event.Event;
import dev.worldreplay.core.event.EventDecoder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * /wr 命令：录制库与回放入口。
 *
 * <p>子命令：
 * <ul>
 *   <li>/wr gui —— 服务端扫描 recordings 目录并把文件列表推给执行者，
 *       客户端收到 OpenRecordLibrary + RecordList 后打开录制库（v0.1.9 起无 O 键）</li>
 *   <li>/wr &lt;file&gt; —— 服务端加载校验并把录制分片推给执行者，
 *       客户端 ReplayVisualizer 直接开始回放（v0.2.0 修：旧实现只解析计数、
 *       把 events 丢掉，命令报"Loaded N events"却什么都不放）</li>
 * </ul></p>
 */
public final class WorldReplayCommands {

    /** 注册到服务端命令树。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wr")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("gui").executes(ctx -> {
                    // Bug 1 修复：服务端扫描 recordings 目录，把列表发给执行者，
                    // 客户端收到 OpenRecordLibrary + RecordList 后打开 GUI
                    // （不再读客户端本地目录；v0.1.9 起也不再依赖 O 键）
                    CommandSourceStack source = ctx.getSource();
                    if (source.getPlayer() != null) {
                        RecordControlHandler.openRecordLibrary(source.getPlayer());
                    }
                    return 1;
                }))
                .then(Commands.argument("file", StringArgumentType.string())
                        .executes(ctx -> replay(ctx.getSource(), StringArgumentType.getString(ctx, "file")))));
    }

    private static int replay(CommandSourceStack src, String file) {
        // 防目录穿越
        if (!RecordControlHandler.isSafeFileName(file)) {
            src.sendFailure(Component.literal("[WorldReplay] Invalid file name"));
            return 0;
        }
        // 目录统一取自 RecordControlHandler.recordingsDir（此处曾有第二份字面量，
        // 与录制落盘、清理任务各写一遍，改一处就漂移）
        Path dir = RecordControlHandler.recordingsDir(src.getServer());
        Path f = dir.resolve(RecordControlHandler.hasWrpSuffix(file) ? file : file + ".wrp");
        if (!Files.isRegularFile(f)) {
            src.sendFailure(Component.literal("[WorldReplay] Not found: " + f.getFileName()));
            return 0;
        }
        try {
            byte[] data = Files.readAllBytes(f);
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            byte ver = EventDecoder.readHeader(in);
            if (ver <= 0) {
                src.sendFailure(Component.literal("[WorldReplay] Bad magic (not an WorldReplay recording)"));
                return 0;
            }
            List<Event> events = new ArrayList<>();
            int bad = 0;
            while (in.available() > 0) {
                Event ev = EventDecoder.readFrom(in);
                if (ev != null) events.add(ev);
                else bad++;
            }
            if (events.isEmpty()) {
                src.sendFailure(Component.literal("[WorldReplay] Nothing to replay: " + f.getFileName()
                        + " has no readable events"));
                return 0;
            }
            // 交给客户端回放：与 GUI 的"回放"按钮走同一条数据通路（分片下发，
            // 客户端 ClientPayloadHandlers -> ReplayVisualizer.begin）
            if (src.getPlayer() == null) {
                src.sendFailure(Component.literal("[WorldReplay] Replay needs a player (run it in-game)"));
                return 0;
            }
            int chunks = RecordControlHandler.sendRecording(src.getPlayer(), f.getFileName().toString(), data);
            final String msg = "[WorldReplay] Replaying " + events.size() + " events ("
                    + bad + " skipped) from " + f.getFileName() + " (" + chunks + " chunks)";
            src.sendSuccess(() -> Component.literal(msg), false);
            return events.size();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[WorldReplay] Load failed: " + ex.getMessage()));
            return 0;
        }
    }

    private WorldReplayCommands() {}
}