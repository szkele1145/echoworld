package dev.worldreplay.fabric12111;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端定时清理：每隔 {@link #CLEANUP_INTERVAL_TICKS} tick（约 5 分钟）扫描一次
 * {@code worldreplay/recordings} 目录，删除超过 {@link WorldReplayConfig#AUTO_CLEANUP_DAYS}
 * 天的 .wrp 录制文件。days<=0 时不执行任何清理。
 */
public final class WorldReplayServerTick {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReplayServerTick.class);
    /** 清理扫描间隔：6000 tick ≈ 5 分钟（20 tick/秒）。 */
    private static final int CLEANUP_INTERVAL_TICKS = 6000;
    private static int ticksSinceCleanup = 0;

    private WorldReplayServerTick() {}

    /** 注册服务端 tick 监听（由 WorldReplayFabric 在 onInitialize 中调用）。 */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> onServerTick(server));
    }

    public static void onServerTick(MinecraftServer server) {
        int days = WorldReplayConfig.AUTO_CLEANUP_DAYS;
        if (days <= 0) return;
        if (++ticksSinceCleanup < CLEANUP_INTERVAL_TICKS) return;
        ticksSinceCleanup = 0;
        cleanupOldRecordings(days);
    }

    private static void cleanupOldRecordings(int days) {
        Path dir = RecordControlHandler.recordingsDir();
        if (!Files.isDirectory(dir)) return;
        long cutoff = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000L;
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String name = f.getFileName().toString();
                if (!RecordControlHandler.hasWrpSuffix(name) || !Files.isRegularFile(f)) continue;
                try {
                    long modified = Files.getLastModifiedTime(f).toMillis();
                    if (modified < cutoff) {
                        Files.deleteIfExists(f);
                        LOGGER.info("[WorldReplay] Auto-cleaned expired recording {}", name);
                    }
                } catch (IOException e) {
                    LOGGER.warn("[WorldReplay] Failed to auto-clean {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[WorldReplay] Auto-clean scan failed on {}: {}", dir, e.getMessage());
        }
    }
}
