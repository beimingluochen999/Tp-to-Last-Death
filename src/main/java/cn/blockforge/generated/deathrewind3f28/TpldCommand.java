package cn.blockforge.generated.deathrewind3f28;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.Set;

/**
 * /tpld [玩家名]：把指定玩家传送回上一次死亡的位置。
 * 不写玩家名时默认传送执行指令的玩家自己。
 */
public final class TpldCommand {

    private static final String ARG_PLAYER = "player";

    /** Tab 补全：列出当前在线玩家的名字。 */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_NAMES = (context, builder) -> {
        for (String name : context.getSource().getOnlinePlayerNames()) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    };

    private TpldCommand() {
    }

    /** 在模组初始化时调用。 */
    public static void init() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpld")
                // 不带参数：传给执行指令的玩家自己
                .executes(TpldCommand::teleportExecutor)
                .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                        .suggests(PLAYER_NAMES)
                        .executes(TpldCommand::teleportNamed)));
    }

    private static int teleportExecutor(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            // 控制台 / 命令方块没有自己的死亡位置，必须写玩家名
            source.sendFailure(Component.literal("该指令只能由玩家执行，请在指令后写上玩家名")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        return teleport(source, player);
    }

    private static int teleportNamed(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, ARG_PLAYER);
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(name);
        if (target == null) {
            // 玩家不在线：拿不到他的死亡位置，按同一条提示处理
            return fail(source, name);
        }
        ServerPlayer executor = source.getPlayer();
        // 自己传自己谁都可以；传别人需要管理员（2 级）权限
        if (executor != target && !Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
            source.sendFailure(Component.literal("你没有权限把其他玩家传送至其上次死亡位置")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        return teleport(source, target);
    }

    /** 真正的传送逻辑；任何一步失败都只回一条提示，不改动玩家状态。 */
    private static int teleport(CommandSourceStack source, ServerPlayer target) {
        String name = target.getName().getString();

        // 正在死亡界面里的玩家传送没有意义，等复活后位置会跟着带过去
        if (!target.isAlive()) {
            return fail(source, name);
        }

        Optional<GlobalPos> deathLocation = target.getLastDeathLocation();
        if (deathLocation.isEmpty()) {
            // 从没死过，或者存档里没有记录
            return fail(source, name);
        }

        GlobalPos globalPos = deathLocation.get();
        ServerLevel level = source.getServer().getLevel(globalPos.dimension());
        if (level == null) {
            // 记录里的维度已经不在了
            return fail(source, name);
        }

        BlockPos pos = globalPos.pos();
        // 虚空死亡时原版照样会记下坐标，只是坐标落在世界底部以下：这种位置不能传
        if (level.isOutsideBuildHeight(pos)) {
            return fail(source, name);
        }

        boolean moved = target.teleportTo(level,
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), target.getYRot(), target.getXRot(), true);
        if (!moved) {
            return fail(source, name);
        }

        source.sendSuccess(() -> Component.literal("已将" + name + "传送至上一次死亡位置："
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + "（" + globalPos.dimension().identifier() + "）"), true);
        if (source.getPlayer() != target) {
            target.sendSystemMessage(Component.literal("你被传送到了上一次死亡的位置"));
        }
        return 1;
    }

    private static int fail(CommandSourceStack source, String name) {
        source.sendFailure(Component.literal("无法将" + name + "传送至上次死亡位置")
                .withStyle(ChatFormatting.RED));
        return 0;
    }
}
