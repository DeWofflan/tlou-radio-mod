package com.tlouradio.item;

import com.tlouradio.block.RadioBaseBoxBlock;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.radio.RadioClientHooks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

public class HandheldRadioItem extends Item {
	public HandheldRadioItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity entity) {
		return false;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		Level level = context.getLevel();
		BlockPos clickedPos = context.getClickedPos();
		if (level.getBlockState(clickedPos).getBlock() instanceof RadioBaseBoxBlock) {
			return RadioBaseBoxBlock.tryPairHandheldRadio(level, clickedPos, player, context.getItemInHand());
		}
		return InteractionResult.PASS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!player.isAlive() || player.isSpectator()) {
			return InteractionResult.FAIL;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			RadioClientHooks.openHandheldRadioScreen(hand, stack);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(
			ItemStack stack,
			TooltipContext context,
			TooltipDisplay tooltipDisplay,
			Consumer<Component> tooltipAdder,
			TooltipFlag flag
	) {
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		Component statusText = state.isPowered()
				? Component.translatable("tooltip.tlouradio.status_on").withStyle(ChatFormatting.GREEN)
				: Component.translatable("tooltip.tlouradio.status_off").withStyle(ChatFormatting.RED);

		tooltipAdder.accept(Component.translatable("tooltip.tlouradio.power", statusText).withStyle(ChatFormatting.GRAY));
		tooltipAdder.accept(Component.translatable("tooltip.tlouradio.frequency", state.frequency()).withStyle(ChatFormatting.GRAY));

		if (state.networkId().isPresent()) {
			String shortId = RadioStateComponent.shortNetworkId(state.networkId().get());
			tooltipAdder.accept(Component.translatable("tooltip.tlouradio.network_paired", shortId).withStyle(ChatFormatting.AQUA));
		} else {
			tooltipAdder.accept(Component.translatable("tooltip.tlouradio.network_open").withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
