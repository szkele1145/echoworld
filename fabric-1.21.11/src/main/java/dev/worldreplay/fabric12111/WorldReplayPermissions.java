package dev.worldreplay.fabric12111;

import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * WorldReplay Fabric 1.21.11 权限检查辅助。
 *
 * <p>1.21.9 起 Mojang 把"权限等级"重构成了 {@link Permission} /
 * {@link PermissionPredicate} 体系：{@code player.hasPermissionLevel(int)} 和
 * {@code source.hasPermissionLevel(int)} 都被移除。</p>
 *
 * <p>OP（等级 2）对应 {@link PermissionLevel#GAMEMASTERS}。这里统一封装，
 * 避免各处散落构造对象的样板代码。</p>
 */
public final class WorldReplayPermissions {

    /** OP 权限（等级 2）。 */
    private static final Permission OP_LEVEL =
            new Permission.Level(PermissionLevel.GAMEMASTERS);

    private WorldReplayPermissions() {}

    /** 玩家是否拥有 OP 权限（等级 ≥ 2）。 */
    public static boolean isOperator(ServerPlayerEntity player) {
        return player.getPermissions().hasPermission(OP_LEVEL);
    }

    /** 命令来源是否拥有 OP 权限（等级 ≥ 2）。 */
    public static boolean isOperator(ServerCommandSource source) {
        return source.getPermissions().hasPermission(OP_LEVEL);
    }
}
