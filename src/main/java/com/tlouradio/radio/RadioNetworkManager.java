package com.tlouradio.radio;

import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.RadioChannelKey;
import com.tlouradio.network.payload.RadioReceivingSignalPayload;
import com.tlouradio.network.payload.SyncRadioTransmitterPayload;
import com.tlouradio.sound.ModSounds;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
	public record DroppedRadioEntry(
			UUID entityUuid,
			ItemEntity entity,
			ServerLevel level,
			RadioChannelKey channel
	) {
	}

	private static final long RX_SIGNAL_RATE_LIMIT_MS = 150L;
	private static final long DROPPED_PARTICLE_RATE_LIMIT_MS = 250L;

	private static final ConcurrentHashMap<RadioChannelKey, Set<UUID>> CHANNEL_LISTENERS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Set<RadioChannelKey>> PLAYER_SUBSCRIPTIONS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, RadioChannelKey> ACTIVE_TRANSMITTERS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Long> LAST_RX_SIGNAL_MS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<RadioChannelKey, Set<DroppedRadioEntry>> DROPPED_RADIOS_BY_CHANNEL = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, DroppedRadioEntry> DROPPED_RADIO_INDEX = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Long> LAST_DROPPED_PARTICLE_MS = new ConcurrentHashMap<>();
	private static final Set<UUID> ONLINE_PLAYERS = ConcurrentHashMap.newKeySet();

	private static volatile MinecraftServer activeServer;

	private RadioNetworkManager() {
	}

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> activeServer = server);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			activeServer = server;
			ServerPlayer player = handler.getPlayer();
			ONLINE_PLAYERS.add(player.getUUID());
			syncPlayer(player);
			sendTransmitterSnapshotTo(player);
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

		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof ItemEntity itemEntity) {
				trackDroppedRadio(world, itemEntity);
			}
		});

		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof ItemEntity itemEntity) {
				untrackDroppedRadio(itemEntity.getUUID());
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			clearAll();
			activeServer = null;
		});
	}

	/**
	 * O(1) validation of a radio state against Overworld {@link RadioBaseNetworkSavedData}.
	 * Claimed Base Box frequencies require matching networkId + passcode; unclaimed frequencies operate freely.
	 */
	public static boolean isRadioStateAuthorized(MinecraftServer server, RadioStateComponent state) {
		if (state == null || !state.isPowered() || !state.antennaExtended()) {
			return false;
		}
		if (server == null) {
			return state.networkId().isEmpty();
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
			if (ACTIVE_TRANSMITTERS.remove(playerId) != null) {
				broadcastTransmitterState(player.level().getServer(), playerId, false);
			}
			return;
		}

		Optional<RadioChannelKey> heldChannel = findHeldPoweredRadioChannel(player);
		if (heldChannel.isPresent()) {
			ACTIVE_TRANSMITTERS.put(playerId, heldChannel.get());
		} else {
			if (ACTIVE_TRANSMITTERS.remove(playerId) != null) {
				playRadioSound(player, ModSounds.RADIO_PTT_END, 0.30F, 1.0F);
				broadcastTransmitterState(player.level().getServer(), playerId, false);
			}
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
		MinecraftServer server = player.level().getServer();
		if (!active) {
			if (ACTIVE_TRANSMITTERS.remove(playerId) != null) {
				if (player.isAlive() && !player.hasDisconnected()) {
					playRadioSound(player, ModSounds.RADIO_PTT_END, 0.30F, 1.0F);
				}
				broadcastTransmitterState(server, playerId, false);
			}
			return false;
		}
		if (!player.isAlive() || player.isSpectator() || player.hasDisconnected()) {
			if (ACTIVE_TRANSMITTERS.remove(playerId) != null) {
				broadcastTransmitterState(server, playerId, false);
			}
			return false;
		}

		Optional<RadioChannelKey> heldChannel = findHeldPoweredRadioChannel(player);
		if (heldChannel.isEmpty()) {
			if (ACTIVE_TRANSMITTERS.remove(playerId) != null) {
				broadcastTransmitterState(server, playerId, false);
			}
			if (holdsRetractedAntennaPoweredRadio(player)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.antenna_retracted_no_signal").withStyle(ChatFormatting.GOLD)
				);
			} else if (holdsEncryptedOrWrongPasscodeRadio(player)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.frequency_encrypted_wrong_passcode").withStyle(ChatFormatting.RED)
				);
			} else if (holdsDisconnectedPairedPoweredRadio(player)) {
				player.sendOverlayMessage(
						Component.translatable("message.tlouradio.connection_lost").withStyle(ChatFormatting.RED)
				);
			}
			return false;
		}

		syncPlayer(player);
		boolean wasActive = ACTIVE_TRANSMITTERS.put(playerId, heldChannel.get()) != null;
		if (!wasActive) {
			playRadioSound(player, ModSounds.RADIO_PTT_START, 0.35F, 1.0F);
			broadcastTransmitterState(server, playerId, true);
		}
		return true;
	}

	/**
	 * Sabotage & credential-change invalidation hook.
	 * Drops ONLY online listeners and active PTT transmitters on {@code networkId} whose specific
	 * channel is no longer valid (when destroyed, all channels of {@code networkId} are invalid).
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

		// Also purge any dropped ground radios on invalidated channels of this Base Box
		for (DroppedRadioEntry dropped : DROPPED_RADIO_INDEX.values()) {
			RadioChannelKey ch = dropped.channel();
			if (networkId.equals(ch.networkId().orElse(null)) && !savedData.isValidChannel(ch)) {
				untrackDroppedRadio(dropped.entityUuid());
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
				playRadioSound(player, ModSounds.RADIO_SABOTAGE, 0.45F, 1.0F);
			} else {
				removePlayer(playerId);
			}
		}
	}

	/**
	 * Revalidates any online players currently operating in free-frequency mode ({@code networkId.isEmpty()})
	 * on frequencies newly claimed by a Base Box so they cannot continue listening/transmitting unauthenticated.
	 */
	public static void revalidateFreeListenersForFrequencies(MinecraftServer server, Set<String> claimedFrequencies) {
		if (server == null || claimedFrequencies == null || claimedFrequencies.isEmpty()) {
			return;
		}
		Set<String> normalized = new HashSet<>(claimedFrequencies.size());
		for (String f : claimedFrequencies) {
			normalized.add(RadioStateComponent.normalizeFrequency(f));
		}

		Set<UUID> affectedPlayerIds = new HashSet<>();
		for (Map.Entry<UUID, Set<RadioChannelKey>> entry : PLAYER_SUBSCRIPTIONS.entrySet()) {
			for (RadioChannelKey channel : entry.getValue()) {
				if (channel.networkId().isEmpty() && normalized.contains(channel.frequency())) {
					affectedPlayerIds.add(entry.getKey());
					break;
				}
			}
		}
		for (Map.Entry<UUID, RadioChannelKey> entry : ACTIVE_TRANSMITTERS.entrySet()) {
			RadioChannelKey channel = entry.getValue();
			if (channel.networkId().isEmpty() && normalized.contains(channel.frequency())) {
				affectedPlayerIds.add(entry.getKey());
			}
		}

		for (DroppedRadioEntry dropped : DROPPED_RADIO_INDEX.values()) {
			RadioChannelKey ch = dropped.channel();
			if (ch.networkId().isEmpty() && normalized.contains(ch.frequency())) {
				untrackDroppedRadio(dropped.entityUuid());
			}
		}

		if (affectedPlayerIds.isEmpty()) {
			return;
		}

		Component msg = Component.translatable("message.tlouradio.frequency_claimed_disconnect")
				.withStyle(ChatFormatting.RED);
		for (UUID playerId : affectedPlayerIds) {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null && !player.hasDisconnected()) {
				syncPlayer(player);
				player.sendOverlayMessage(msg);
				playRadioSound(player, ModSounds.RADIO_SABOTAGE, 0.40F, 1.0F);
			} else {
				removePlayer(playerId);
			}
		}
	}

	/**
	 * Rate-limited S2C pulse called from Simple Voice Chat's UDP thread when a radio audio packet
	 * is delivered to {@code receiverId}. Safely hops to the server thread and caps rate to 1 packet / 150ms.
	 */
	public static void notifyReceivingSignal(UUID receiverId) {
		if (receiverId == null) {
			return;
		}
		MinecraftServer server = activeServer;
		if (server == null || !ONLINE_PLAYERS.contains(receiverId)) {
			return;
		}
		long now = System.currentTimeMillis();
		Long prev = LAST_RX_SIGNAL_MS.get(receiverId);
		if (prev != null && (now - prev) < RX_SIGNAL_RATE_LIMIT_MS) {
			return;
		}
		if (prev == null) {
			if (LAST_RX_SIGNAL_MS.putIfAbsent(receiverId, now) != null) {
				return;
			}
		} else if (!LAST_RX_SIGNAL_MS.replace(receiverId, prev, now)) {
			return;
		}

		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(receiverId);
			if (player != null && !player.hasDisconnected()
					&& ServerPlayNetworking.canSend(player, RadioReceivingSignalPayload.TYPE)) {
				ServerPlayNetworking.send(player, RadioReceivingSignalPayload.INSTANCE);
			}
		});
	}

	public static void removePlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}
		LAST_RX_SIGNAL_MS.remove(playerId);
		boolean wasTransmitting = ACTIVE_TRANSMITTERS.remove(playerId) != null;
		Set<RadioChannelKey> previous = PLAYER_SUBSCRIPTIONS.remove(playerId);
		if (previous != null) {
			for (RadioChannelKey channel : previous) {
				removeListenerFromChannel(channel, playerId);
			}
		}
		if (wasTransmitting) {
			broadcastTransmitterState(activeServer, playerId, false);
		}
	}

	public static void clearAll() {
		ACTIVE_TRANSMITTERS.clear();
		PLAYER_SUBSCRIPTIONS.clear();
		CHANNEL_LISTENERS.clear();
		LAST_RX_SIGNAL_MS.clear();
		DROPPED_RADIOS_BY_CHANNEL.clear();
		DROPPED_RADIO_INDEX.clear();
		LAST_DROPPED_PARTICLE_MS.clear();
		ONLINE_PLAYERS.clear();
	}

	public static void trackDroppedRadio(ServerLevel level, ItemEntity itemEntity) {
		if (level == null || itemEntity == null || itemEntity.isRemoved()) {
			return;
		}
		ItemStack stack = itemEntity.getItem();
		if (stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return;
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		if (!isRadioStateAuthorized(level.getServer(), state)) {
			return;
		}
		UUID entityId = itemEntity.getUUID();
		RadioChannelKey channel = state.toChannelKey();
		DroppedRadioEntry entry = new DroppedRadioEntry(entityId, itemEntity, level, channel);
		DroppedRadioEntry previous = DROPPED_RADIO_INDEX.put(entityId, entry);
		if (previous != null) {
			removeDroppedFromChannel(previous.channel(), previous);
		}
		DROPPED_RADIOS_BY_CHANNEL.computeIfAbsent(channel, ignored -> ConcurrentHashMap.newKeySet()).add(entry);
	}

	public static void untrackDroppedRadio(UUID entityUuid) {
		if (entityUuid == null) {
			return;
		}
		LAST_DROPPED_PARTICLE_MS.remove(entityUuid);
		DroppedRadioEntry removed = DROPPED_RADIO_INDEX.remove(entityUuid);
		if (removed != null) {
			removeDroppedFromChannel(removed.channel(), removed);
		}
	}

	private static void removeDroppedFromChannel(RadioChannelKey channel, DroppedRadioEntry entry) {
		DROPPED_RADIOS_BY_CHANNEL.computeIfPresent(channel, (key, set) -> {
			set.remove(entry);
			return set.isEmpty() ? null : set;
		});
	}

	/**
	 * Thread-safe snapshot of active dropped ground radios tuned to {@code channel}, called on SVC's UDP thread.
	 */
	public static Set<DroppedRadioEntry> getDroppedRadios(RadioChannelKey channel) {
		Set<DroppedRadioEntry> entries = DROPPED_RADIOS_BY_CHANNEL.get(channel);
		if (entries == null || entries.isEmpty()) {
			return Collections.emptySet();
		}
		return Set.copyOf(entries);
	}

	/**
	 * Rate-limited (250 ms) visual speaker spark/signal effect above an actively emitting dropped ground radio.
	 */
	public static void emitDroppedRadioAudioEffect(DroppedRadioEntry entry) {
		if (entry == null) {
			return;
		}
		MinecraftServer server = activeServer;
		if (server == null) {
			return;
		}
		UUID entityId = entry.entityUuid();
		long now = System.currentTimeMillis();
		Long prev = LAST_DROPPED_PARTICLE_MS.get(entityId);
		if (prev != null && (now - prev) < DROPPED_PARTICLE_RATE_LIMIT_MS) {
			return;
		}
		if (prev == null) {
			if (LAST_DROPPED_PARTICLE_MS.putIfAbsent(entityId, now) != null) {
				return;
			}
		} else if (!LAST_DROPPED_PARTICLE_MS.replace(entityId, prev, now)) {
			return;
		}

		server.execute(() -> {
			ItemEntity entity = entry.entity();
			if (entity == null || entity.isRemoved() || !entity.isAlive()) {
				untrackDroppedRadio(entityId);
				return;
			}
			Vec3 pos = entity.position();
			entry.level().sendParticles(
					ParticleTypes.ELECTRIC_SPARK,
					pos.x,
					pos.y + 0.42D,
					pos.z,
					2,
					0.09D,
					0.06D,
					0.09D,
					0.01D
			);
			entry.level().sendParticles(
					ParticleTypes.NOTE,
					pos.x,
					pos.y + 0.56D,
					pos.z,
					1,
					0.06D,
					0.04D,
					0.06D,
					0.0D
			);
		});
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

	private static void sendTransmitterSnapshotTo(ServerPlayer player) {
		if (player == null || player.hasDisconnected()) {
			return;
		}
		if (!ServerPlayNetworking.canSend(player, SyncRadioTransmitterPayload.TYPE)) {
			return;
		}
		List<UUID> snapshot = new ArrayList<>(ACTIVE_TRANSMITTERS.keySet());
		ServerPlayNetworking.send(player, SyncRadioTransmitterPayload.fullSnapshot(snapshot));
	}

	private static void broadcastTransmitterState(MinecraftServer server, UUID playerId, boolean transmitting) {
		if (server == null || playerId == null) {
			return;
		}
		Runnable sendTask = () -> {
			SyncRadioTransmitterPayload payload = SyncRadioTransmitterPayload.singleUpdate(playerId, transmitting);
			for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
				if (!recipient.hasDisconnected() && ServerPlayNetworking.canSend(recipient, SyncRadioTransmitterPayload.TYPE)) {
					ServerPlayNetworking.send(recipient, payload);
				}
			}
		};
		if (server.isSameThread()) {
			sendTask.run();
		} else {
			server.execute(sendTask);
		}
	}

	private static void playRadioSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		player.level().playSound(
				null,
				player.getX(),
				player.getY(),
				player.getZ(),
				sound,
				SoundSource.PLAYERS,
				volume,
				pitch
		);
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

	private static boolean holdsEncryptedOrWrongPasscodeRadio(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return isEncryptedOrWrongPasscodeRadio(server, player.getMainHandItem())
				|| isEncryptedOrWrongPasscodeRadio(server, player.getOffhandItem());
	}

	private static boolean isEncryptedOrWrongPasscodeRadio(MinecraftServer server, ItemStack stack) {
		if (server == null || stack == null || stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return false;
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		if (!state.isPowered() || !state.antennaExtended()) {
			return false;
		}
		if (state.networkId().isPresent()) {
			return false;
		}
		return RadioBaseNetworkSavedData.get(server).lookupFrequency(state.frequency()).isPresent();
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
