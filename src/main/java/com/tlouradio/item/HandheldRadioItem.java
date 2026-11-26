package com.tlouradio.item;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
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

public class HandheldRadioItem extends Item implements GeoItem {
	private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

	private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
	private volatile Object cachedRenderProvider;

	public HandheldRadioItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean isPerspectiveAware() {
		return true;
	}

	@Override
	public Object getRenderProvider() {
		Object provider = this.cachedRenderProvider;
		if (provider == null) {
			provider = RadioClientHooks.createHandheldRenderProvider();
			this.cachedRenderProvider = provider;
		}
		return provider != null ? provider : GeoItem.super.getRenderProvider();
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>("handheld_controller", 2, state -> state.setAndContinue(IDLE_ANIM)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.geoCache;
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
		Component antennaText = state.antennaExtended()
				? Component.translatable("tooltip.tlouradio.antenna_open").withStyle(ChatFormatting.GREEN)
				: Component.translatable("tooltip.tlouradio.antenna_closed").withStyle(ChatFormatting.GOLD);

		tooltipAdder.accept(Component.translatable("tooltip.tlouradio.power", statusText).withStyle(ChatFormatting.GRAY));
		tooltipAdder.accept(Component.translatable("tooltip.tlouradio.frequency", state.frequency()).withStyle(ChatFormatting.GRAY));
		tooltipAdder.accept(Component.translatable("tooltip.tlouradio.antenna", antennaText).withStyle(ChatFormatting.GRAY));

		if (state.networkId().isPresent()) {
			String shortId = RadioStateComponent.shortNetworkId(state.networkId().get());
			tooltipAdder.accept(Component.translatable("tooltip.tlouradio.network_paired", shortId).withStyle(ChatFormatting.AQUA));
		} else {
			tooltipAdder.accept(Component.translatable("tooltip.tlouradio.network_open").withStyle(ChatFormatting.DARK_GRAY));
		}

		if (state.hotMicLocked()) {
			tooltipAdder.accept(Component.translatable("tooltip.tlouradio.hot_mic_locked").withStyle(ChatFormatting.RED));
		}
	}
}
