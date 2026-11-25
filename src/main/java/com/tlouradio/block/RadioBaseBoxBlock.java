package com.tlouradio.block;

import com.tlouradio.block.entity.RadioBaseBoxBlockEntity;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.OpenBaseBoxScreenPayload;
import com.tlouradio.radio.RadioBaseNetworkSavedData;
import com.tlouradio.radio.RadioNetworkManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class RadioBaseBoxBlock extends Block implements EntityBlock {
	public RadioBaseBoxBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new RadioBaseBoxBlockEntity(pos, state);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!level.isClientSide() && !oldState.is(state.getBlock()) && level instanceof ServerLevel serverLevel) {
			if (serverLevel.getBlockEntity(pos) instanceof RadioBaseBoxBlockEntity baseBox) {
				baseBox.ensureRegistered(serverLevel);
			}
		}
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
			if (serverLevel.getBlockEntity(pos) instanceof RadioBaseBoxBlockEntity baseBox) {
				baseBox.ensureRegistered(serverLevel);
			}
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		BlockState newState = level.getBlockState(pos);
		if (!newState.is(state.getBlock())) {
			GlobalPos globalPos = GlobalPos.of(level.dimension(), pos.immutable());
			RadioBaseNetworkSavedData savedData = RadioBaseNetworkSavedData.get(level.getServer());
			savedData.unregisterByPosition(globalPos).ifPresent(removedNetworkId ->
					RadioNetworkManager.invalidateBaseNetwork(level.getServer(), removedNetworkId, true)
			);
		}
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}

	@Override
	protected InteractionResult useItemOn(
			ItemStack stack,
			BlockState state,
			Level level,
			BlockPos pos,
			Player player,
			InteractionHand hand,
			BlockHitResult hitResult
	) {
		ItemStack radioStack = null;
		if (stack.getItem() instanceof HandheldRadioItem) {
			radioStack = stack;
		} else if (hand == InteractionHand.MAIN_HAND && player.getOffhandItem().getItem() instanceof HandheldRadioItem) {
			radioStack = player.getOffhandItem();
		}

		if (radioStack == null) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}

		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!serverPlayer.isAlive() || serverPlayer.isSpectator() || !serverPlayer.isWithinBlockInteractionRange(pos, 4.0D)) {
			return InteractionResult.FAIL;
		}
		if (!(serverLevel.getBlockEntity(pos) instanceof RadioBaseBoxBlockEntity baseBox)) {
			return InteractionResult.FAIL;
		}

		baseBox.ensureRegistered(serverLevel);

		RadioStateComponent current = radioStack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		RadioStateComponent paired = current.withPairedNetwork(
				baseBox.getNetworkId(),
				baseBox.getFrequency(),
				baseBox.getPasscode()
		);
		radioStack.set(ModDataComponents.RADIO_STATE, paired);

		RadioNetworkManager.syncPlayer(serverPlayer);

		serverLevel.playSound(
				null,
				pos,
				SoundEvents.NOTE_BLOCK_BIT.value(),
				SoundSource.BLOCKS,
				0.6F,
				1.5F
		);

		Component pairedMsg = Component.translatable(
				"message.tlouradio.paired_to_base",
				baseBox.getFrequency(),
				RadioStateComponent.shortNetworkId(baseBox.getNetworkId())
		).withStyle(ChatFormatting.AQUA);
		serverPlayer.sendOverlayMessage(pairedMsg);

		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(
			BlockState state,
			Level level,
			BlockPos pos,
			Player player,
			BlockHitResult hitResult
	) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!serverPlayer.isAlive() || serverPlayer.isSpectator() || !serverPlayer.isWithinBlockInteractionRange(pos, 4.0D)) {
			return InteractionResult.FAIL;
		}
		if (!(serverLevel.getBlockEntity(pos) instanceof RadioBaseBoxBlockEntity baseBox)) {
			return InteractionResult.FAIL;
		}

		baseBox.ensureRegistered(serverLevel);

		ServerPlayNetworking.send(
				serverPlayer,
				new OpenBaseBoxScreenPayload(
						pos.immutable(),
						baseBox.getNetworkId(),
						baseBox.getFrequency(),
						baseBox.getPasscode()
				)
		);
		return InteractionResult.SUCCESS;
	}
}
