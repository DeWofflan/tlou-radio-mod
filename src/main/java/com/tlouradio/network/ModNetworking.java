package com.tlouradio.network;

import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.RadioPttPayload;
import com.tlouradio.network.payload.ToggleRadioPowerPayload;
import com.tlouradio.radio.RadioNetworkManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class ModNetworking {
	private ModNetworking() {
	}

	public static void initialize() {
		PayloadTypeRegistry.serverboundPlay().register(
				ToggleRadioPowerPayload.TYPE,
				ToggleRadioPowerPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
				RadioPttPayload.TYPE,
				RadioPttPayload.STREAM_CODEC
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

			Component statusMessage = updated.isPowered()
					? Component.translatable("message.tlouradio.power_on", updated.frequency()).withStyle(ChatFormatting.GREEN)
					: Component.translatable("message.tlouradio.power_off").withStyle(ChatFormatting.RED);
			player.sendOverlayMessage(statusMessage);
		});

		ServerPlayNetworking.registerGlobalReceiver(RadioPttPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			RadioNetworkManager.handlePttRequest(player, payload.active());
		});
	}
}
