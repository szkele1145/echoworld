package dev.echoworld.nf1211;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * EchoWorld NeoForge 1.21.1 主入口。
 *
 * <p>平台骨架：仅验证 mod 装载链路，行为捕获/回放对接在后续迭代实现。</p>
 */
@Mod(EchoWorldNeoForge.MOD_ID)
public class EchoWorldNeoForge {

    public static final String MOD_ID = "echoworld";
    private static final Logger LOGGER = LogUtils.getLogger();

    public EchoWorldNeoForge() {
        LOGGER.info("[EchoWorld] NeoForge 1.21.1 loaded");
    }
}
