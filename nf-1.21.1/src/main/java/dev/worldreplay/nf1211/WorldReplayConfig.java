package dev.worldreplay.nf1211;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * WorldReplay 服务端配置（写入 {@code config/worldreplay.toml}）。
 *
 * <p>当前仅暴露自动清理策略；后续可在此追加更多服务端行为开关。</p>
 */
public final class WorldReplayConfig {

    public static final ModConfigSpec SPEC;
    /** 自动清理录制文件的天数（0 表示禁用，不自动删除）。 */
    public static final ModConfigSpec.IntValue AUTO_CLEANUP_DAYS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("general");
        AUTO_CLEANUP_DAYS = builder
                .comment("自动清理录制文件的天数（0 表示禁用，不自动删除）")
                .defineInRange("autoCleanupDays", 30, 0, 365);
        builder.pop();
        SPEC = builder.build();
    }

    private WorldReplayConfig() {}
}
