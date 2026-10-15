package dev.worldreplay.nf1211;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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
@EventBusSubscriber(modid = WorldReplayNeoForge.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class WorldReplayServerTick {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldReplayServerTick.class);
    /** 清理扫描间隔：6000 tick ≈ 5 分钟（20 tick/秒）。 */
    private static final int CLEANUP_INTERVAL_TICKS = 6000;
    private static int ticksSinceCleanup = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int days = WorldReplayConfig.AUTO_CLEANUP_DAYS.get();
        if (days <= 0) return;                          // 配置为 0：禁用自动清理
        if (++ticksSinceCleanup < CLEANUP_INTERVAL_TICKS) return;
        ticksSinceCleanup = 0;
        cleanupOldRecordings(event.getServer().getServerDirectory(), days);
    }

    private static void cleanupOldRecordings(Path serverDir, int days) {
        Path dir = serverDir.resolve("worldreplay").resolve("recordings");
        if (!Files.isDirectory(dir)) return;
        long cutoff = System.currentTimeMillis() - (long) days * 24 * 60 * 60 * 1000L;
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".wrp") || !Files.isRegularFile(f)) continue;
                try {
                    long modified = Files.getLastModifiedTime(f).toMillis();
                    if (modified < cutoff) {
                        Files.deleteIfExists(f);
                        LOGGER.info("[WorldReplay] Auto-cleaned expired recording {}", name);
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private WorldReplayServerTick() {}
}
