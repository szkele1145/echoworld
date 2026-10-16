package dev.worldreplay.nf1211;

import com.mojang.logging.LogUtils;
import dev.worldreplay.nf1211.client.ClientPayloadHandlers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 录制控制包处理：C2S 开/停录制指定玩家（GUI Start/Stop 按钮的服务端侧），
 * 以及列表 / 回放 / 删除请求（Bug 1 修复：录制文件在服务端，GUI 走网络分发）。
 *
 * <p>注册为 optional——没装 WorldReplay 的客户端不受影响。
 * players 为空 + start=true = 全体在线玩家（Select All）。</p>
 *
 * <p>S2C 通道的 handler 以方法引用指向 {@link ClientPayloadHandlers}——
 * 该类只会在客户端收到包时初始化，其客户端专属引用（Minecraft 等）
 * 不会在专用服务器上触发类初始化，安全。</p>
 */
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID)
public final class RecordControlHandler {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /**
     * 仅大小写改名时的中转名（NTFS 上必须两步才真正改条目名）。
     * 刻意不用 {@code .wrp} 结尾——万一中途崩溃残留，也不会被
     * {@link #scanRecordings} 当成一条录制列进 GUI。
     */
    static final String WR_RENAME_TMP = ".wr-rename.tmp";

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(RecordControlPackets.RecordControl.TYPE,
                RecordControlPackets.RecordControl.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handle(payload, sp);
                }));
        // S2C：服务端录制文件列表（Bug 1）
        registrar.playToClient(RecordListPackets.RecordList.TYPE,
                RecordListPackets.RecordList.CODEC,
                (payload, ctx) -> ClientPayloadHandlers.handleRecordList(payload));
        // S2C：.wrp 文件数据分片（回放请求的响应，Bug 1）
        registrar.playToClient(RecordListPackets.RecordingData.TYPE,
                RecordListPackets.RecordingData.CODEC,
                (payload, ctx) -> ClientPayloadHandlers.handleRecordingData(payload));
        // C2S：请求文件列表（v0.1.8 及更早客户端开 GUI 时用；v0.1.9 已不发，保留兼容）
        registrar.playToServer(RecordListPackets.RequestRecordList.TYPE,
                RecordListPackets.RequestRecordList.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handleListRequest(sp);
                }));
        // C2S：请求回放 / 删除某录制文件（Bug 1）
        registrar.playToServer(RecordListPackets.RequestRecording.TYPE,
                RecordListPackets.RequestRecording.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handleRecordingRequest(payload, sp);
                }));
        // C2S：重命名某录制文件（v0.1.9，独立通道：老服务端收到未知通道按 optional 丢弃）
        registrar.playToServer(RecordListPackets.RequestRename.TYPE,
                RecordListPackets.RequestRename.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) handleRenameRequest(payload, sp);
                }));
        // S2C：/wr gui 的开屏信号（v0.1.9，取代 O 键；必须先于 RecordList 到达）
        registrar.playToClient(RecordListPackets.OpenRecordLibrary.TYPE,
                RecordListPackets.OpenRecordLibrary.CODEC,
                (payload, ctx) -> ClientPayloadHandlers.handleOpenRecordLibrary(payload));
    }

    // ---- 列表 / 文件请求（Bug 1）----

    /** 服务端录制目录。 */
    public static Path recordingsDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("worldreplay").resolve("recordings");
    }

    /** 服务端录制目录（从服务器目录 Path 直接构造，供模组外/清理任务复用）。 */
    public static Path recordingsDir(Path serverDir) {
        return serverDir.resolve("worldreplay").resolve("recordings");
    }

    /** 扫描服务端 recordings 目录（.wrp，按修改时间倒序；含事件数快速估算）。 */
    public static List<RecordListPackets.RecordListEntry> scanRecordings(MinecraftServer server) {
        List<RecordListPackets.RecordListEntry> entries = new ArrayList<>();
        Path dir = recordingsDir(server);
        if (!Files.isDirectory(dir)) return entries;
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.filter(p -> {
                // 后缀大小写不敏感：旧实现用 endsWith(".wrp")，手工改名成 .WRP 的录制
                // 会从 GUI 列表里凭空消失（文件还在，但扫不到、也删不掉）
                String n = p.getFileName().toString();
                return hasWrpSuffix(n) && Files.isRegularFile(p);
            })::iterator) {
                try {
                    entries.add(new RecordListPackets.RecordListEntry(
                            f.getFileName().toString(),
                            Files.size(f),
                            Files.getLastModifiedTime(f).toMillis(),
                            countEvents(f)));
                } catch (IOException ignored) {}
            }
        } catch (Exception ignored) {}
        entries.sort((a, b) -> Long.compare(b.modified(), a.modified()));
        return entries;
    }

    /** 是否是 .wrp 录制文件（后缀比较不区分大小写）。 */
    static boolean hasWrpSuffix(String name) {
        return name != null && name.length() > 4
                && name.regionMatches(true, name.length() - 4, ".wrp", 0, 4);
    }

    /** 快速统计 .wrp 内的事件数：逐条跳过字段（不解码内容，只读长度前缀）。 */
    private static int countEvents(Path f) {
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f)))) {
            if (dev.worldreplay.core.event.EventDecoder.readHeader(in) <= 0) return 0;
            int count = 0;
            while (in.available() > 0) {
                in.readLong();              // tick
                skipUtf(in);                 // type
                skipUtf(in);                 // playerId
                skipUtf(in);                 // dimension
                in.readDouble(); in.readDouble(); in.readDouble();   // xyz
                skipUtf(in);                 // data
                count++;
            }
            return count;
        } catch (Exception e) {
            // 损坏/半截文件：以前静默返回 0，GUI 显示"0 事件"却看不出原因
            LOGGER.warn("[WorldReplay] Corrupt or truncated recording: {}", f.getFileName());
            return 0;
        }
    }

    private static void skipUtf(java.io.DataInputStream in) throws java.io.IOException {
        int len = in.readUnsignedShort();
        int skipped = in.skipBytes(len);
        // DataInputStream.skipBytes 内部会循环到底，正常情况 skipped == len；
        // 若字节流被截断则不足，此时直接报错交给上层当损坏处理，而不是让后续
        // readLong/readDouble 读到错位数据后算出天文数字的事件数
        if (skipped != len) throw new java.io.EOFException("truncated string field");
    }

    /** 把列表发给玩家（/wr gui 分支与 C2S 请求共用）。 */
    public static void sendRecordList(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new RecordListPackets.RecordList(scanRecordings(player.server)));
    }

    /**
     * /wr gui：先发开屏信号，再发列表（v0.1.9）。
     *
     * <p>顺序不可颠倒：客户端以 {@code OpenRecordLibrary} 置位“开新屏”，
     * 随后的 {@code RecordList} 才据此构建并显示界面。</p>
     */
    public static void openRecordLibrary(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new RecordListPackets.OpenRecordLibrary());
        sendRecordList(player);
    }

    /** C2S RequestRecordList：权限校验后回发列表。 */
    private static void handleListRequest(ServerPlayer sender) {
        if (!sender.hasPermissions(2)) return;   // OP 限定（与 GUI 控制一致）
        sendRecordList(sender);
    }

    /** C2S RequestRename：改名后无条件重发列表（v0.1.9，独立通道）。 */
    private static void handleRenameRequest(RecordListPackets.RequestRename payload, ServerPlayer sender) {
        if (!sender.hasPermissions(2)) return;   // OP 限定，与删除/回放一致
        String fileName = payload.fileName();
        String newName = payload.newName();
        if (!isSafeFileName(fileName)) return;
        if (newName == null || newName.isEmpty()) return;
        Path dir = recordingsDir(sender.server);
        Path f = dir.resolve(fileName);
        if (Files.isRegularFile(f)) {
            renameRecording(dir, f, newName, sender);
        }
        sendRecordList(sender);   // 无论成功与否都刷新（客户端以服务端状态为准）
    }

    /** C2S RequestRecording：replay=分片回传文件数据，delete=删文件后重发列表。 */
    private static void handleRecordingRequest(RecordListPackets.RequestRecording payload, ServerPlayer sender) {
        if (!sender.hasPermissions(2)) return;   // OP 限定
        String fileName = payload.fileName();
        // 防目录穿越（与 /echoreplay 校验一致）
        if (!isSafeFileName(fileName)) return;
        Path dir = recordingsDir(sender.server);
        Path f = dir.resolve(fileName);
        if (!Files.isRegularFile(f)) {
            sendRecordList(sender);   // 文件已不存在：刷新列表让 GUI 同步
            return;
        }
        if (payload.action() == RecordListPackets.RequestRecording.ACTION_DELETE) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception e) {
                // 文件被占用 / 只读：以前静默吞掉，玩家点了删除却什么都没发生也没有解释
                LOGGER.warn("[WorldReplay] Delete failed: {}", f.getFileName(), e);
                notifyFailure(sender, "worldreplay.delete.failed");
            }
            sendRecordList(sender);   // 删除后刷新
            return;
        }
        // ACTION_REPLAY：读文件、分片下发给请求者
        try {
            byte[] data = Files.readAllBytes(f);
            if (data.length == 0) {
                notifyFailure(sender, "worldreplay.replay.failed");
                return;
            }
            sendRecording(sender, fileName, data);
        } catch (Exception e) {
            LOGGER.warn("[WorldReplay] Replay read failed: {}", f.getFileName(), e);
            notifyFailure(sender, "worldreplay.replay.failed");
        }
    }

    /**
     * 把录制字节按 {@link RecordListPackets#MAX_CHUNK} 分片顺序下发（TCP 保序），返回分片数。
     *
     * <p>GUI 的"回放"按钮与 {@code /wr <file>} 共用这一条通路，
     * 保证两条入口在客户端落到同一个 {@code ReplayVisualizer.begin}。</p>
     */
    static int sendRecording(ServerPlayer target, String fileName, byte[] data) {
        int chunkIndex = 0;
        for (int off = 0; off < data.length; ) {
            int len = Math.min(RecordListPackets.MAX_CHUNK, data.length - off);
            boolean last = off + len >= data.length;
            PacketDistributor.sendToPlayer(target, new RecordListPackets.RecordingData(
                    fileName, chunkIndex++, last, java.util.Arrays.copyOfRange(data, off, off + len)));
            off += len;
        }
        return chunkIndex;
    }

    /** 文件名安全校验：拒绝路径分隔符 / .. / 空名（目录穿越防护，列表与重命名共用）。 */
    static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        return !name.contains(":") && !name.contains("*") && !name.contains("?")
                && !name.contains("\"") && !name.contains("<") && !name.contains(">")
                && !name.contains("|");
    }

    /**
     * 重命名录制文件（v0.1.9，v0.2.0 修三处缺陷）。
     *
     * <p>客户端传来的新名只作"显示名"处理：服务端统一清洗（去掉路径分隔符、
     * 控制字符与 Windows 保留字符、去空白、限长 96），强制补 {@code .wrp} 后缀，
     * 已存在同名文件时追加 {@code -2}、{@code -3}… 递增，绝不覆盖既有录制。</p>
     *
     * <p>v0.2.0 修复：
     * <ol>
     *   <li>大小写不敏感文件系统上，"仅改大小写"的改名曾被 {@code Files.exists}
     *       误判为同名冲突，产出 {@code FOO-2.wrp}；</li>
     *   <li>NTFS 上直接 {@code move} 到仅大小写不同的目标不会真正改名
     *       （实测磁盘条目仍是原名），改为"临时名 + 目标名"两步移动；</li>
     *   <li>序号用尽时旧实现把已占用的路径交给 {@code Files.move}，
     *       抛出的异常被空 catch 吞掉——静默失败且无任何提示。</li>
     * </ol></p>
     */
    private static void renameRecording(Path dir, Path src, String requested, ServerPlayer sender) {
        String cleaned = sanitizeDisplayName(requested);
        if (cleaned.isEmpty()) {
            notifyFailure(sender, "worldreplay.rename.invalid");
            return;
        }
        Path dst = freeTarget(dir, src, cleaned);
        if (dst == null) {
            notifyFailure(sender, "worldreplay.rename.exhausted");
            return;
        }
        if (dst.getFileName().toString().equals(src.getFileName().toString())) {
            return;   // 名字与磁盘上的完全一致：免 IO
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
        } catch (Exception e) {
            LOGGER.warn("[WorldReplay] Rename failed: {} -> {}", src.getFileName(), dst.getFileName(), e);
            notifyFailure(sender, "worldreplay.rename.failed");
            try {
                Files.deleteIfExists(dir.resolve(WR_RENAME_TMP));   // 清理可能残留的中间名
            } catch (Exception ignored) {}
        }
    }

    /**
     * 求一个不冲突的目标路径；可用名字耗尽时返回 {@code null}（调用方据此上报，不再让异常被吞）。
     *
     * <p>关键差异：{@code Files.exists(dst)} 在大小写不敏感的文件系统上，
     * 对"仅大小写不同的源文件自身"同样返回 true。旧实现因此把 {@code foo.wrp}
     * 改名为 {@code FOO} 时误判冲突，产出 {@code FOO-2.wrp}。
     * 这里用 {@link Files#isSameFile} 排除"目标即源文件自身"。</p>
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

    /** 重命名失败时给操作者一句明确反馈（旧实现是彻底静默）。 */
    private static void notifyFailure(ServerPlayer sender, String translationKey) {
        sender.sendSystemMessage(Component.translatable(translationKey));
    }

    /** 清洗客户端提交的显示名：去非法字符、折叠空白、限长（空串=无效）。 */
    static String sanitizeDisplayName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < 96; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;                        // 控制字符
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|') continue;   // 路径/保留字符
            sb.append(c);
        }
        String s = sb.toString().trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);     // Windows 不允许尾点
        // 后缀由服务端补：大小写不敏感地剥掉（旧实现用 endsWith(".wrp")，
        // 于是 "Kept.WRP" 逃过剥离、落盘成 "Kept.WRP.wrp"）
        if (s.length() >= 4 && s.regionMatches(true, s.length() - 4, ".wrp", 0, 4)) {
            s = s.substring(0, s.length() - 4);
            while (s.endsWith(".")) s = s.substring(0, s.length() - 1);   // 剥离后可能又露出尾点
        }
        s = s.trim();
        if (s.length() > 96) s = s.substring(0, 96);
        return s;
    }

    private static void handle(RecordControlPackets.RecordControl payload, ServerPlayer sender) {
        if (sender == null || !sender.hasPermissions(2)) return;   // OP 限定
        MinecraftServer server = sender.server;
        List<UUID> targets = new ArrayList<>(payload.players());
        // 空列表 = 全体在线（start 与 stop 对称——修：全选停止不再漏人）
        if (targets.isEmpty()) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) targets.add(sp.getUUID());
        }
        for (UUID id : targets) {
            if (payload.start()) {
                WorldReplayRecorder.startFor(server, id);
            } else {
                WorldReplayRecorder.stopFor(server, id);
            }
        }
    }

    private RecordControlHandler() {}
}
