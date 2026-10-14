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
 * /echoreplay 命令：加载与校验 .wrp 录制。
 *
 * <p>子命令：
 * <ul>
 *   <li>/echoreplay gui —— 提示 GUI 打开方式（客户端 O 键；专用服无 GUI）</li>
 *   <li>/echoreplay <file> —— 服务端加载并校验录制，反馈事件数</li>
 * </ul></p>
 */
public final class WorldReplayCommands {

    /** 注册到服务端命令树。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("echoreplay")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("gui").executes(ctx -> {
                    // Bug 1 修复：服务端扫描 recordings 目录，把列表发给执行者，
                    // 客户端收到 RecordList 后打开 GUI（不再读客户端本地目录）
                    CommandSourceStack source = ctx.getSource();
                    if (source.getPlayer() != null) {
                        RecordControlHandler.sendRecordList(source.getPlayer());
                    }
                    source.sendSuccess(() ->
                            Component.translatable("worldreplay.gui.hint"), false);
                    return 1;
                }))
                .then(Commands.argument("file", StringArgumentType.string())
                        .executes(ctx -> replay(ctx.getSource(), StringArgumentType.getString(ctx, "file")))));
    }

    private static int replay(CommandSourceStack src, String file) {
        // 防目录穿越
        if (file.contains("/") || file.contains("\\") || file.contains("..")) {
            src.sendFailure(Component.literal("[WorldReplay] Invalid file name"));
            return 0;
        }
        try {
            Path dir = src.getServer().getServerDirectory().resolve("worldreplay").resolve("recordings");
            Path f = dir.resolve(file.endsWith(".wrp") ? file : file + ".wrp");
            if (!Files.isRegularFile(f)) {
                src.sendFailure(Component.literal("[WorldReplay] Not found: " + f.getFileName()));
                return 0;
            }
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
            final String msg = "[WorldReplay] Loaded " + events.size() + " events ("
                    + bad + " skipped) from " + f.getFileName();
            src.sendSuccess(() -> Component.literal(msg), false);
            return events.size();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("[WorldReplay] Load failed: " + ex.getMessage()));
            return 0;
        }
    }

    private WorldReplayCommands() {}
}