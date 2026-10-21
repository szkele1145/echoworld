package dev.worldreplay.fabric1192.mixin;

import dev.worldreplay.fabric1192.WorldReplayRecorder;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 捕获方块放置事件（Fabric 侧）。
 *
 * <p><b>为什么需要 Mixin</b>：Fabric API 没有"放置"事件
 * （只有 {@code PlayerBlockBreakEvents} 与 {@code UseBlockCallback}），
 * 而 NeoForge 侧用 {@code BlockEvent.EntityPlaceEvent} 直接能拿到
 * {@code block_place}。没有这个 Mixin，Fabric 版的回放里
 * <b>永远只有"使用"标记、没有"放置"标记</b>——同一份录制在两个
 * 平台上表现不同。</p>
 *
 * <p><b>注入点</b>：{@code BlockItem.postPlacement(BlockPos, World, ItemStack, BlockState)}
 * ——方块真正放入世界后的后处理，返回 {@code true} 表示成功。</p>
 *
 * <p><b>为什么不用 {@code place}</b>：{@code BlockItem} 有<b>两个</b>名为
 * {@code place} 的重载（{@code place(ItemPlacementContext)→ActionResult}
 * 与 {@code place(ItemPlacementContext, BlockState)→boolean}）。
 * Mixin 的 {@code method = "place"} 只按名字匹配，refmap 会选中<b>前者</b>
 * （返回 ActionResult 的那个），与本注入点的签名不匹配——运行时会找不到
 * 目标方法。{@code postPlacement} 名字唯一，没有这个歧义。</p>
 *
 * <p>映射说明：{@code postPlacement} 在 yarn 1.19.4 与 1.20.1 中
 * <b>同名同签名</b>，所以两个平台共用同一份源码。</p>
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {

    @Inject(method = "postPlacement", at = @At("RETURN"))
    private void worldreplay$capturePlace(BlockPos pos, World world, PlayerEntity player,
                                          ItemStack stack, BlockState placed,
                                          CallbackInfoReturnable<Boolean> cir) {
        // 放置失败（被阻挡等）不记录
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        try {
            if (world.isClient) return;
            if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity sp)) return;
            WorldReplayRecorder.capturePlace(sp, placed, pos.getX(), pos.getY(), pos.getZ());
        } catch (Throwable ignored) {
            // 录制是旁路功能，任何异常都不能影响游戏
        }
    }
}
