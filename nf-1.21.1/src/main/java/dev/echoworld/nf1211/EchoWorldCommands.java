package dev.echoworld.nf1211;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.echoworld.core.event.Event;
import dev.echoworld.core.event.EventDecoder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * /echoreplay 命令：从 .echoworld/recordings/ 加载 .ecw 录制并回放给命令执行者。
 *
 * <p>当前阶段：验证录制文件可读（解析事件数并反馈）。
 * 世界内视觉重现由 ReplayVisualizer（下一迭代）接手。</p>
 */
public final class EchoWorldCommands {

    /** 注册到服务端命令树。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("echoreplay")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("file", StringArgumentType.string())
                        .executes(ctx -> replay(ctx.getSource(), StringArgumentType.getString(ctx, "file")))));
    }

    private static int replay(CommandSourceStack src, String file) {
        // 防目录穿越
        if (file.contains("/") || file.contains("\\") || file.contains("..")) {
            src.sendFailure(Component.literal("[EchoWorld] Invalid file name"));
            return 0;
        }
        try {
            Path dir = src.getServer().getServerDirectory().resolve("echoworld").resolve("recordings");
            Path f = dir.resolve(file.endsWith(".ecw") ? file : file + ".ecw");
            if (!Files.isRegularFile(f)) {
                src.sendFailure(Component.literal("[EchoWorld] Not found: " + f.getFileName()));
                return 0;
            }
            byte[] data = Files.readAllBytes(f);
            // 解码验证（EventDecoder 带校验）
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
            byte ver = EventDecoder.readHeader(in);
            if (ver <= 0) {
                src.sendFailure(Component.literal("[EchoWorld] Bad magic (not an EchoWorld recording)"));
                return 0;
            }
            List<Event> events = new ArrayList<>();
            Event e;
            int bad = 0;
            while (in.available() > 0) {
                Event ev = EventDecoder.readFrom(in);
                if (ev != null) events.add(ev);
                else bad++;
            }
            final String msg = "[EchoWorld] Loaded " + events.size() + " events (" + bad + " skipped) from " + f.getFileName();
            src.sendSuccess(() -> Component.literal(msg), false);
            return events.size();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[EchoWorld] Load failed: " + ex.getMessage()));
            return 0;
        }
    }

    private EchoWorldCommands() {}
}
