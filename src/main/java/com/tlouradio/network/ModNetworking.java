package com.tlouradio.network;

import com.tlouradio.block.entity.RadioBaseBoxBlockEntity;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.BaseBoxSaveResultPayload;
import com.tlouradio.network.payload.OpenBaseBoxScreenPayload;
import com.tlouradio.network.payload.RadioPttPayload;
import com.tlouradio.network.payload.RadioReceivingSignalPayload;
import com.tlouradio.network.payload.SetRadioAntennaPayload;
import com.tlouradio.network.payload.StepRadioDialPayload;
import com.tlouradio.network.payload.SyncRadioTransmitterPayload;
import com.tlouradio.network.payload.ToggleRadioPowerPayload;
import com.tlouradio.network.payload.UpdateBaseBoxConfigPayload;
import com.tlouradio.network.payload.UpdateHandheldRadioPayload;
import com.tlouradio.radio.RadioBaseNetworkSavedData;
import com.tlouradio.radio.RadioNetworkManager;
import com.tlouradio.sound.ModSounds;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

public final class ModNetworking {
	private ModNetworking() {
	}

	public static void initialize() {
		PayloadTypeRegistry.clientboundPlay().register(
				OpenBaseBoxScreenPayload.TYPE,
				OpenBaseBoxScreenPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
				SyncRadioTransmitterPayload.TYPE,
				SyncRadioTransmitterPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
				RadioReceivingSignalPayload.TYPE,
				RadioReceivingSignalPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
				BaseBoxSaveResultPayload.TYPE,
				BaseBoxSaveResultPayload.STREAM_CODEC
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
		PayloadTypeRegistry.serverboundPlay().register(
				SetRadioAntennaPayload.TYPE,
				SetRadioAntennaPayload.STREAM_CODEC
		);
		PayloadTypeRegistry.serverboundPlay().register(
				StepRadioDialPayload.TYPE,
				StepRadioDialPayload.STREAM_CODEC
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

			player.level().playSound(
					null,
					player.getX(),
					player.getY(),
					player.getZ(),
					updated.isPowered() ? ModSounds.RADIO_POWER_ON : ModSounds.RADIO_POWER_OFF,
					SoundSource.PLAYERS,
					0.45F,
					1.0F
			);

			MinecraftServer server = player.level().getServer();
			Component statusMessage;
			if (!updated.isPowered()) {
				statusMessage = Component.translatable("message.tlouradio.power_off").withStyle(ChatFormatting.RED);
			} else if (!updated.antennaExtended()) {
				statusMessage = Component.translatable("message.tlouradio.power_on_antenna_closed", updated.frequency())
						.withStyle(ChatFormatting.GOLD);
			} else if (!RadioNetworkManager.isRadioStateAuthorized(server, updated)) {
				boolean claimedUnpaired = updated.networkId().isEmpty()
						&& server != null
						&& RadioBaseNetworkSavedData.get(server).lookupFrequency(updated.frequency()).isPresent();
				statusMessage = Component.translatable(
						claimedUnpaired
								? "message.tlouradio.frequency_encrypted_wrong_passcode"
								: "message.tlouradio.power_on_no_signal",
						updated.frequency()
				).withStyle(ChatFormatting.GOLD);
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

			if (payload.channels() == null || payload.channels().isEmpty()
					|| payload.channels().size() > RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX) {
				Component err = Component.translatable("message.tlouradio.base_box_invalid_input").withStyle(ChatFormatting.RED);
				sendBaseBoxSaveResult(player, false, err);
				return;
			}

			RadioBaseBoxBlockEntity.UpdateResult result = baseBox.updateConfiguration(
					serverLevel,
					payload.channels(),
					payload.selectedChannelIndex()
			);

			if (!result.success()) {
				Component err;
				if (result.conflictFrequency().isPresent()) {
					err = Component.translatable("message.tlouradio.base_box_freq_conflict", result.conflictFrequency().get())
							.withStyle(ChatFormatting.RED);
				} else if (result.duplicateInRequest()) {
					err = Component.translatable("message.tlouradio.base_box_freq_duplicate").withStyle(ChatFormatting.RED);
				} else {
					err = Component.translatable("message.tlouradio.base_box_invalid_input").withStyle(ChatFormatting.RED);
				}
				sendBaseBoxSaveResult(player, false, err);
				return;
			}

			MinecraftServer server = serverLevel.getServer();
			if (result.channelsChanged()) {
				RadioNetworkManager.invalidateBaseNetwork(server, baseBox.getNetworkId(), false);
			}
			if (!result.newlyClaimedFrequencies().isEmpty()) {
				RadioNetworkManager.revalidateFreeListenersForFrequencies(server, result.newlyClaimedFrequencies());
			}

			Component okMsg = Component.translatable(
					"message.tlouradio.base_box_saved_multi",
					baseBox.getSelectedChannel().frequency(),
					baseBox.getChannels().size()
			).withStyle(ChatFormatting.GREEN);
			player.sendOverlayMessage(okMsg);
			sendBaseBoxSaveResult(player, true, okMsg);
		});

		ServerPlayNetworking.registerGlobalReceiver(UpdateHandheldRadioPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ItemStack stack = getValidatedHeldRadio(player, payload.hand());
			if (stack == null) {
				return;
			}

			String rawFrequency = payload.frequency();
			String passcode = payload.passcode();
			if (!RadioStateComponent.isValidFrequency(rawFrequency) || !RadioStateComponent.isValidPasscode(passcode)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.base_box_invalid_input").withStyle(ChatFormatting.RED)
				);
				return;
			}

			String frequency = RadioStateComponent.normalizeFrequency(rawFrequency);
			RadioStateComponent current = stack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			boolean wasPaired = current.networkId().isPresent();

			applyAutoResolvedHandheldTuning(
					player,
					stack,
					current.isPowered(),
					frequency,
					passcode,
					payload.antennaExtended(),
					wasPaired,
					false
			);
		});

		ServerPlayNetworking.registerGlobalReceiver(SetRadioAntennaPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ItemStack stack = getValidatedHeldRadio(player, payload.hand());
			if (stack == null) {
				return;
			}

			RadioStateComponent current = stack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			if (current.antennaExtended() == payload.antennaExtended()) {
				return;
			}

			RadioStateComponent updated = current.withAntennaExtended(payload.antennaExtended());
			stack.set(ModDataComponents.RADIO_STATE, updated);
			RadioNetworkManager.syncPlayer(player);

			Component msg = payload.antennaExtended()
					? Component.translatable("message.tlouradio.antenna_extended").withStyle(ChatFormatting.GREEN)
					: Component.translatable("message.tlouradio.antenna_retracted").withStyle(ChatFormatting.GOLD);
			player.sendOverlayMessage(msg);
		});

		ServerPlayNetworking.registerGlobalReceiver(StepRadioDialPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ItemStack stack = getValidatedHeldRadio(player, payload.hand());
			if (stack == null) {
				return;
			}

			String rawFrequency = payload.frequency();
			if (!RadioStateComponent.isValidFrequency(rawFrequency)) {
				return;
			}
			String frequency = RadioStateComponent.normalizeFrequency(rawFrequency);

			RadioStateComponent current = stack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			boolean wasPaired = current.networkId().isPresent();
			// Do not carry over a previously paired Base Box's secret PIN when manually detuning away
			String effectivePasscode = wasPaired ? "" : current.passcode();

			applyAutoResolvedHandheldTuning(
					player,
					stack,
					current.isPowered(),
					frequency,
					effectivePasscode,
					current.antennaExtended(),
					wasPaired,
					true
			);
		});
	}

	/**
	 * Resolves a handheld radio's target (frequency, passcode) against O(1) Base Box frequency ownership:
	 * <ul>
	 *   <li>Unclaimed frequency → free mode ({@code networkId = Optional.empty()}).</li>
	 *   <li>Claimed frequency + matching passcode → auto-pairs to that Base Box ({@code KUTU:XXXX}).</li>
	 *   <li>Claimed frequency + wrong passcode → blocked ({@code networkId = Optional.empty()}, rejected by auth) + warning.</li>
	 * </ul>
	 */
	private static void applyAutoResolvedHandheldTuning(
			ServerPlayer player,
			ItemStack stack,
			boolean isPowered,
			String normalizedFrequency,
			String passcode,
			boolean antennaExtended,
			boolean wasPaired,
			boolean fromDial
	) {
		MinecraftServer server = player.level().getServer();
		Optional<RadioBaseNetworkSavedData.FrequencyOwner> ownerOpt = server != null
				? RadioBaseNetworkSavedData.get(server).lookupFrequency(normalizedFrequency)
				: Optional.empty();

		Optional<UUID> resolvedNetworkId = Optional.empty();
		boolean autoPairedToBase = false;
		boolean wrongPasscodeForBase = false;

		if (ownerOpt.isPresent()) {
			RadioBaseNetworkSavedData.FrequencyOwner owner = ownerOpt.get();
			if (owner.passcode().equals(passcode)) {
				resolvedNetworkId = Optional.of(owner.networkId());
				autoPairedToBase = true;
			} else {
				wrongPasscodeForBase = true;
			}
		}

		RadioStateComponent updated = new RadioStateComponent(
				isPowered,
				normalizedFrequency,
				resolvedNetworkId,
				passcode,
				antennaExtended
		);
		stack.set(ModDataComponents.RADIO_STATE, updated);
		RadioNetworkManager.syncPlayer(player);

		Component statusMessage;
		if (!antennaExtended) {
			statusMessage = Component.translatable("message.tlouradio.handheld_updated_antenna_closed", normalizedFrequency)
					.withStyle(ChatFormatting.GOLD);
		} else if (autoPairedToBase) {
			statusMessage = Component.translatable(
					"message.tlouradio.auto_paired_to_base",
					normalizedFrequency,
					RadioStateComponent.shortNetworkId(resolvedNetworkId.get())
			).withStyle(ChatFormatting.AQUA);
		} else if (wrongPasscodeForBase) {
			statusMessage = Component.translatable(
					"message.tlouradio.frequency_encrypted_wrong_passcode_freq",
					normalizedFrequency
			).withStyle(ChatFormatting.RED);
		} else if (fromDial) {
			statusMessage = wasPaired
					? Component.translatable("message.tlouradio.handheld_updated_unpaired", normalizedFrequency).withStyle(ChatFormatting.AQUA)
					: Component.translatable("message.tlouradio.dial_tuned", normalizedFrequency).withStyle(ChatFormatting.GREEN);
		} else if (wasPaired) {
			statusMessage = Component.translatable("message.tlouradio.handheld_updated_unpaired", normalizedFrequency)
					.withStyle(ChatFormatting.AQUA);
		} else if (!passcode.isEmpty()) {
			statusMessage = Component.translatable("message.tlouradio.handheld_updated_pin", normalizedFrequency)
					.withStyle(ChatFormatting.GREEN);
		} else {
			statusMessage = Component.translatable("message.tlouradio.handheld_updated_open", normalizedFrequency)
					.withStyle(ChatFormatting.GREEN);
		}
		player.sendOverlayMessage(statusMessage);
	}

	private static void sendBaseBoxSaveResult(ServerPlayer player, boolean success, Component message) {
		if (player != null && !player.hasDisconnected()
				&& ServerPlayNetworking.canSend(player, BaseBoxSaveResultPayload.TYPE)) {
			ServerPlayNetworking.send(player, new BaseBoxSaveResultPayload(success, message));
		}
	}

	private static ItemStack getValidatedHeldRadio(ServerPlayer player, InteractionHand hand) {
		if (player == null || hand == null || !player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
			return null;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return null;
		}
		return stack;
	}
}
