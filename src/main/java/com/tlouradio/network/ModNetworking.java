package com.tlouradio.network;

import com.tlouradio.block.entity.RadioBaseBoxBlockEntity;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.OpenBaseBoxScreenPayload;
import com.tlouradio.network.payload.RadioPttPayload;
import com.tlouradio.network.payload.ToggleRadioPowerPayload;
import com.tlouradio.network.payload.UpdateBaseBoxConfigPayload;
import com.tlouradio.network.payload.UpdateHandheldRadioPayload;
import com.tlouradio.radio.RadioNetworkManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public final class ModNetworking {
	private ModNetworking() {
	}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(
				OpenBaseBoxScreenPayload.TYPE,
				OpenBaseBoxScreenPayload.STREAM_CODEC
		);

		PayloadTypeRegistry.serverboundPlay().register(
				ToggleRadioPowerPayload.TYPE,
				ToggleRadioPowerPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
				RadioPttPayload.TYPE,
				RadioPttPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
				UpdateBaseBoxConfigPayload.TYPE,
				UpdateBaseBoxConfigPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
				UpdateHandheldRadioPayload.TYPE,
				UpdateHandheldRadioPayload.STREAM_CODEC
		);

		ServerPlayNetworking.registerGlobalReceiver(ToggleRadioPowerPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!player.isAlive() || player.isSpectator()) {
				return;
			}

			ItemStack mainHandStack = player.getMainHandItem();
			if (mainHandStack.isEmpty() || !(mainHandStack.getItem() instanceof HandheldRadioItem)) {
				return;
			}

			RadioStateComponent current = mainHandStack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			RadioStateComponent updated = current.withPowered(!current.isPowered());
			mainHandStack.set(ModDataComponents.RADIO_STATE, updated);

			RadioNetworkManager.syncPlayer(player);

			Component statusMessage;
			if (!updated.isPowered()) {
				statusMessage = Component.translatable("message.tlouradio.power_off").withStyle(ChatFormatting.RED);
			} else if (updated.networkId().isPresent()
					&& !RadioNetworkManager.isRadioStateAuthorized(player.level().getServer(), updated)) {
				statusMessage = Component.translatable("message.tlouradio.power_on_no_signal", updated.frequency())
						.withStyle(ChatFormatting.GOLD);
			} else {
				statusMessage = Component.translatable("message.tlouradio.power_on", updated.frequency())
						.withStyle(ChatFormatting.GREEN);
			}
			player.sendOverlayMessage(statusMessage);
		});

		ServerPlayNetworking.registerGlobalReceiver(RadioPttPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			RadioNetworkManager.handlePttRequest(player, payload.active());
		});

		ServerPlayNetworking.registerGlobalReceiver(UpdateBaseBoxConfigPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
				return;
			}

			BlockPos pos = payload.pos();
			if (!player.isWithinBlockInteractionRange(pos, 4.0D)) {
				return;
			}

			ServerLevel serverLevel = player.level();
			if (!serverLevel.isLoaded(pos)) {
				return;
			}
			if (!(serverLevel.getBlockEntity(pos) instanceof RadioBaseBoxBlockEntity baseBox)) {
				return;
			}

			String frequency = payload.frequency();
			String passcode = payload.passcode();
			if (!RadioStateComponent.isValidFrequency(frequency) || !RadioStateComponent.isValidPasscode(passcode)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.base_box_invalid_input").withStyle(ChatFormatting.RED)
				);
				return;
			}

			boolean changed = baseBox.updateConfiguration(serverLevel, frequency, passcode);
			if (changed) {
				RadioNetworkManager.invalidateBaseNetwork(serverLevel.getServer(), baseBox.getNetworkId(), false);
			}

			player.sendOverlayMessage(
					Component.translatable("message.tlouradio.base_box_saved", frequency).withStyle(ChatFormatting.GREEN)
			);
		});

		ServerPlayNetworking.registerGlobalReceiver(UpdateHandheldRadioPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
				return;
			}

			InteractionHand hand = payload.hand();
			if (hand == null) {
				return;
			}

			ItemStack stack = player.getItemInHand(hand);
			if (stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
				return;
			}

			String frequency = payload.frequency();
			String passcode = payload.passcode();
			if (!RadioStateComponent.isValidFrequency(frequency) || !RadioStateComponent.isValidPasscode(passcode)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.base_box_invalid_input").withStyle(ChatFormatting.RED)
				);
				return;
			}

			RadioStateComponent current = stack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			boolean wasPaired = current.networkId().isPresent();

			RadioStateComponent updated = new RadioStateComponent(
					current.isPowered(),
					frequency,
					Optional.empty(),
					passcode
			);
			stack.set(ModDataComponents.RADIO_STATE, updated);

			RadioNetworkManager.syncPlayer(player);

			Component statusMessage;
			if (wasPaired) {
				statusMessage = Component.translatable("message.tlouradio.handheld_updated_unpaired", frequency)
						.withStyle(ChatFormatting.AQUA);
			} else if (!passcode.isEmpty()) {
				statusMessage = Component.translatable("message.tlouradio.handheld_updated_pin", frequency)
						.withStyle(ChatFormatting.GREEN);
			} else {
				statusMessage = Component.translatable("message.tlouradio.handheld_updated_open", frequency)
						.withStyle(ChatFormatting.GREEN);
			}
			player.sendOverlayMessage(statusMessage);
		});
	}
}
