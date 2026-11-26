package com.tlouradio.radio;

import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.RadioChannelKey;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-tick, event-driven, thread-safe server RAM registry for active radio channels and PTT transmitters.
 * Safe to query lock-free from Simple Voice Chat's UDP thread without touching Minecraft main-thread objects.
 */
public final class RadioNetworkManager {
	private static final ConcurrentHashMap<RadioChannelKey, Set<UUID>> CHANNEL_LISTENERS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Set<RadioChannelKey>> PLAYER_SUBSCRIPTIONS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, RadioChannelKey> ACTIVE_TRANSMITTERS = new ConcurrentHashMap<>();
	private static final Set<UUID> ONLINE_PLAYERS = ConcurrentHashMap.newKeySet();

	private RadioNetworkManager() {
	}

	public static void initialize() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			ONLINE_PLAYERS.add(player.getUUID());
			syncPlayer(player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.getPlayer().getUUID();
			ONLINE_PLAYERS.remove(playerId);
			removePlayer(playerId);
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
			if (entity instanceof ServerPlayer player) {
				removePlayer(player.getUUID());
			}
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> syncPlayer(newPlayer));

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearAll());
	}

	/**
	 * O(1) validation of a radio state against Overworld {@link RadioBaseNetworkSavedData}.
	 * Open/free-frequency radios ({@code networkId.isEmpty()}) continue to operate freely.
	 */
	public static boolean isRadioStateAuthorized(MinecraftServer server, RadioStateComponent state) {
		if (state == null || !state.isPowered() || !state.antennaExtended()) {
			return false;
		}
		if (state.networkId().isEmpty()) {
			return true;
		}
		if (server == null) {
			return false;
		}
		return RadioBaseNetworkSavedData.get(server).isValidChannel(state.toChannelKey());
	}

	/**
	 * Recomputes a player's active listening channels and validates any ongoing PTT session.
	 * Called strictly on the Minecraft server thread when inventory, equipment, or radio state changes.
	 */
	public static void syncPlayer(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
			removePlayer(playerId);
			return;
		}

		Set<RadioChannelKey> desiredChannels = collectPoweredRadioChannels(player);
		updateSubscriptions(playerId, desiredChannels);
		revalidateHeldPtt(player);
	}

	/**
	 * Re-checks only the player's held hands when hotbar selection changes, cutting PTT immediately
	 * if an active, authorized powered radio is no longer held.
	 */
	public static void revalidateHeldPtt(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		if (!ACTIVE_TRANSMITTERS.containsKey(playerId)) {
			return;
		}
		if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
			ACTIVE_TRANSMITTERS.remove(playerId);
			return;
		}

		Optional<RadioChannelKey> heldChannel = findHeldPoweredRadioChannel(player);
		if (heldChannel.isPresent()) {
			ACTIVE_TRANSMITTERS.put(playerId, heldChannel.get());
		} else {
			ACTIVE_TRANSMITTERS.remove(playerId);
		}
	}

	/**
	 * Server-authoritative PTT toggle handler. Rejects activation if the player is not holding
	 * an authorized powered radio.
	 */
	public static boolean handlePttRequest(ServerPlayer player, boolean active) {
		if (player == null) {
			return false;
		}
		UUID playerId = player.getUUID();
		if (!active) {
			ACTIVE_TRANSMITTERS.remove(playerId);
			return false;
		}
		if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
			ACTIVE_TRANSMITTERS.remove(playerId);
			return false;
		}

		Optional<RadioChannelKey> heldChannel = findHeldPoweredRadioChannel(player);
		if (heldChannel.isEmpty()) {
			ACTIVE_TRANSMITTERS.remove(playerId);
			if (holdsRetractedAntennaPoweredRadio(player)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.antenna_retracted_no_signal").withStyle(ChatFormatting.GOLD)
				);
			} else if (holdsDisconnectedPairedPoweredRadio(player)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.connection_lost").withStyle(ChatFormatting.RED)
				);
			}
			return false;
		}

		syncPlayer(player);
		ACTIVE_TRANSMITTERS.put(playerId, heldChannel.get());
		return true;
	}

	/**
	 * Sabotage & credential-change invalidation hook.
	 * Immediately drops all affected online listeners and active PTT transmitters on {@code networkId}
	 * and notifies impacted players via actionbar + disconnect sound.
	 */
	public static void invalidateBaseNetwork(MinecraftServer server, UUID networkId, boolean destroyed) {
		if (server == null || networkId == null) {
			return;
		}
		RadioBaseNetworkSavedData savedData = RadioBaseNetworkSavedData.get(server);
		Set<UUID> affectedPlayerIds = new HashSet<>();

		for (Map.Entry<UUID, Set<RadioChannelKey>> entry : PLAYER_SUBSCRIPTIONS.entrySet()) {
			for (RadioChannelKey channel : entry.getValue()) {
				if (networkId.equals(channel.networkId().orElse(null)) && !savedData.isValidChannel(channel)) {
					affectedPlayerIds.add(entry.getKey());
					break;
				}
			}
		}

		for (Map.Entry<UUID, RadioChannelKey> entry : ACTIVE_TRANSMITTERS.entrySet()) {
			RadioChannelKey channel = entry.getValue();
			if (networkId.equals(channel.networkId().orElse(null)) && !savedData.isValidChannel(channel)) {
				affectedPlayerIds.add(entry.getKey());
			}
		}

		if (affectedPlayerIds.isEmpty()) {
			return;
		}

		String shortId = RadioStateComponent.shortNetworkId(networkId);
		Component disconnectMessage = Component.translatable(
				destroyed
						? "message.tlouradio.base_destroyed_disconnect"
						: "message.tlouradio.base_reconfigured_disconnect",
				shortId
		).withStyle(ChatFormatting.RED);

		for (UUID playerId : affectedPlayerIds) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null && !player.hasDisconnected()) {
				syncPlayer(player);
				player.sendOverlayMessage(disconnectMessage);
				player.level().playSound(
						null,
						player.getX(),
						player.getY(),
						player.getZ(),
						SoundEvents.BEACON_DEACTIVATE,
						SoundSource.PLAYERS,
						0.35F,
						1.8F
				);
			} else {
				removePlayer(playerId);
			}
		}
	}

	public static void removePlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}
		ACTIVE_TRANSMITTERS.remove(playerId);
		Set<RadioChannelKey> previous = PLAYER_SUBSCRIPTIONS.remove(playerId);
		if (previous != null) {
			for (RadioChannelKey channel : previous) {
				removeListenerFromChannel(channel, playerId);
			}
		}
	}

	public static void clearAll() {
		ACTIVE_TRANSMITTERS.clear();
		PLAYER_SUBSCRIPTIONS.clear();
		CHANNEL_LISTENERS.clear();
		ONLINE_PLAYERS.clear();
	}

	public static boolean isTrackingPlayer(UUID playerId) {
		return playerId != null && (PLAYER_SUBSCRIPTIONS.containsKey(playerId) || ACTIVE_TRANSMITTERS.containsKey(playerId));
	}

	/**
	 * Thread-safe lookup called on SVC's UDP thread.
	 */
	public static Optional<RadioChannelKey> getActiveTransmissionChannel(UUID speakerId) {
		return Optional.ofNullable(ACTIVE_TRANSMITTERS.get(speakerId));
	}

	/**
	 * Thread-safe snapshot lookup called on SVC's UDP thread.
	 */
	public static Set<UUID> getChannelListeners(RadioChannelKey channel) {
		Set<UUID> listeners = CHANNEL_LISTENERS.get(channel);
		if (listeners == null || listeners.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(listeners);
	}

	/**
	 * Thread-safe online UUID view called on SVC's UDP thread when eavesdrop hooks are present.
	 */
	public static Set<UUID> getOnlinePlayers() {
		return Collections.unmodifiableSet(ONLINE_PLAYERS);
	}

	private static Set<RadioChannelKey> collectPoweredRadioChannels(ServerPlayer player) {
		Set<RadioChannelKey> channels = new HashSet<>();
		MinecraftServer server = player.level().getServer();
		Inventory inventory = player.getInventory();
		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			addChannelIfAuthorizedPoweredRadio(server, stack, channels);
		}
		addChannelIfAuthorizedPoweredRadio(server, player.getOffhandItem(), channels);
		return channels;
	}

	private static void addChannelIfAuthorizedPoweredRadio(MinecraftServer server, ItemStack stack, Set<RadioChannelKey> out) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return;
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		if (isRadioStateAuthorized(server, state)) {
			out.add(state.toChannelKey());
		}
	}

	private static Optional<RadioChannelKey> findHeldPoweredRadioChannel(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Optional<RadioChannelKey> mainHand = extractAuthorizedPoweredChannel(server, player.getMainHandItem());
		if (mainHand.isPresent()) {
			return mainHand;
		}
		return extractAuthorizedPoweredChannel(server, player.getOffhandItem());
	}

	private static Optional<RadioChannelKey> extractAuthorizedPoweredChannel(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return Optional.empty();
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		return isRadioStateAuthorized(server, state) ? Optional.of(state.toChannelKey()) : Optional.empty();
	}

	private static boolean holdsRetractedAntennaPoweredRadio(ServerPlayer player) {
		return isRetractedAntennaPoweredRadio(player.getMainHandItem())
				|| isRetractedAntennaPoweredRadio(player.getOffhandItem());
	}

	private static boolean isRetractedAntennaPoweredRadio(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return false;
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		return state.isPowered() && !state.antennaExtended();
	}

	private static boolean holdsDisconnectedPairedPoweredRadio(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return isDisconnectedPairedPoweredRadio(server, player.getMainHandItem())
				|| isDisconnectedPairedPoweredRadio(server, player.getOffhandItem());
	}

	private static boolean isDisconnectedPairedPoweredRadio(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return false;
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		return state.isPowered() && state.networkId().isPresent() && !isRadioStateAuthorized(server, state);
	}

	private static void updateSubscriptions(UUID playerId, Set<RadioChannelKey> desiredChannels) {
		if (desiredChannels.isEmpty()) {
			Set<RadioChannelKey> removed = PLAYER_SUBSCRIPTIONS.remove(playerId);
			if (removed != null) {
				for (RadioChannelKey channel : removed) {
					removeListenerFromChannel(channel, playerId);
				}
			}
			return;
		}

		Set<RadioChannelKey> immutableDesired = Set.copyOf(desiredChannels);
		Set<RadioChannelKey> previous = PLAYER_SUBSCRIPTIONS.put(playerId, immutableDesired);

		if (previous != null) {
			for (RadioChannelKey oldChannel : previous) {
				if (!immutableDesired.contains(oldChannel)) {
					removeListenerFromChannel(oldChannel, playerId);
				}
			}
		}

		for (RadioChannelKey newChannel : immutableDesired) {
			if (previous == null || !previous.contains(newChannel)) {
				CHANNEL_LISTENERS.computeIfAbsent(newChannel, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
			}
		}
	}

	private static void removeListenerFromChannel(RadioChannelKey channel, UUID playerId) {
		CHANNEL_LISTENERS.computeIfPresent(channel, (key, set) -> {
			set.remove(playerId);
			return set.isEmpty() ? null : set;
		});
	}
}
