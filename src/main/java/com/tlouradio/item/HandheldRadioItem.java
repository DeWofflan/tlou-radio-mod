package com.tlouradio.item;

import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
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
	}
}
