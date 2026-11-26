package com.tlouradio.compat;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.api.RadioEavesdropRegistry;
import com.tlouradio.network.RadioChannelKey;
import com.tlouradio.radio.RadioNetworkManager;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class TlouRadioVoicechatPlugin implements VoicechatPlugin {
	public static final String RADIO_CATEGORY_ID = "tlouradio";
	public static final float RADIO_SPEAKER_DISTANCE = 6.0F;
	public static final float DROPPED_RADIO_SPEAKER_DISTANCE = 16.0F;
	private static final double RADIO_EMITTER_HEIGHT_OFFSET = 1.2D;
	private static final double DROPPED_RADIO_HEIGHT_OFFSET = 0.25D;

	@Override
	public String getPluginId() {
		return TlouRadioMod.MOD_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		TlouRadioMod.LOGGER.info("TLOU2 Radio - Simple Voice Chat eklentisi calisma zamaninda basariyla baglandi.");
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
		registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
		registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
	}

	private void onServerStarted(VoicechatServerStartedEvent event) {
		VoicechatServerApi api = event.getVoicechat();
		VolumeCategory radioCategory = api.volumeCategoryBuilder()
				.setId(RADIO_CATEGORY_ID)
				.setName("Tactical Radios")
				.setNameTranslationKey("category.tlouradio.voicechat")
				.setDescription("Incoming tactical radio transmissions")
				.setDescriptionTranslationKey("category.tlouradio.voicechat.description")
				.build();
		api.registerVolumeCategory(radioCategory);
	}

	private void onServerStopped(VoicechatServerStoppedEvent event) {
		event.getVoicechat().unregisterVolumeCategory(RADIO_CATEGORY_ID);
		RadioAudioDspProcessor.clearAll();
	}

	private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
		UUID playerId = event.getPlayerUuid();
		RadioAudioDspProcessor.removeSpeaker(playerId);
		RadioNetworkManager.removePlayer(playerId);
	}

	/**
	 * Executes on Simple Voice Chat's UDP packet thread.
	 * Strictly touches ONLY thread-safe RadioNetworkManager maps and SVC API interfaces;
	 * never touches Minecraft main-thread objects or cancels normal proximity voice chat.
	 */
	private void onMicrophonePacket(MicrophonePacketEvent event) {
		VoicechatConnection senderConnection = event.getSenderConnection();
		if (senderConnection == null) {
			return;
		}
		ServerPlayer speaker = senderConnection.getPlayer();
		if (speaker == null) {
			return;
		}

		UUID speakerId = speaker.getUuid();
		Optional<RadioChannelKey> activeChannelOpt = RadioNetworkManager.getActiveTransmissionChannel(speakerId);
		if (activeChannelOpt.isEmpty()) {
			return;
		}

		RadioChannelKey channel = activeChannelOpt.get();
		Set<UUID> channelListeners = RadioNetworkManager.getChannelListeners(channel);
		Set<RadioNetworkManager.DroppedRadioEntry> droppedRadios = RadioNetworkManager.getDroppedRadios(channel);

		Set<UUID> authorizedReceivers = new LinkedHashSet<>();
		for (UUID listenerId : channelListeners) {
			if (!listenerId.equals(speakerId)) {
				authorizedReceivers.add(listenerId);
			}
		}

		if (RadioEavesdropRegistry.hasHooks()) {
			for (UUID candidateId : RadioNetworkManager.getOnlinePlayers()) {
				if (!candidateId.equals(speakerId)
						&& !authorizedReceivers.contains(candidateId)
						&& RadioEavesdropRegistry.isAuthorized(candidateId, speakerId, channel)) {
					authorizedReceivers.add(candidateId);
				}
			}
		}

		if (authorizedReceivers.isEmpty() && droppedRadios.isEmpty()) {
			return;
		}

		VoicechatServerApi api = event.getVoicechat();
		MicrophonePacket micPacket = event.getPacket();
		byte[] rawOpusData = micPacket.getOpusEncodedData();
		// Execute tactical radio DSP strictly ONCE per speaker packet, outside the receiver loop
		byte[] opusData = RadioAudioDspProcessor.process(api, speakerId, rawOpusData);

		// Determine players already hearing the speaker's live proximity voice to prevent double-audio echo
		double proximityDistance = api.getVoiceChatDistance();
		if (micPacket.isWhispering()) {
			proximityDistance = api.getServerConfig().getDouble("whisper_distance", proximityDistance * 0.5D);
		}

		ServerLevel speakerLevel = speaker.getServerLevel();
		Position speakerPos = speaker.getPosition();
		Set<UUID> playersInDirectProximity = new HashSet<>();
		if (speakerLevel != null && speakerPos != null) {
			for (ServerPlayer nearbyDirect : api.getPlayersInRange(speakerLevel, speakerPos, proximityDistance)) {
				playersInDirectProximity.add(nearbyDirect.getUuid());
			}
		}

		Set<UUID> deliveredRecipients = new HashSet<>();

		for (UUID receiverId : authorizedReceivers) {
			VoicechatConnection receiverConn = api.getConnectionOf(receiverId);
			if (receiverConn == null) {
				continue;
			}
			ServerPlayer receiverPlayer = receiverConn.getPlayer();
			if (receiverPlayer == null) {
				continue;
			}

			ServerLevel receiverLevel = receiverPlayer.getServerLevel();
			Position receiverPos = receiverPlayer.getPosition();
			if (receiverLevel == null || receiverPos == null) {
				continue;
			}

			Position emitterPos = api.createPosition(
					receiverPos.getX(),
					receiverPos.getY() + RADIO_EMITTER_HEIGHT_OFFSET,
					receiverPos.getZ()
			);

			LocationalSoundPacket radioSoundPacket = micPacket.locationalSoundPacketBuilder()
					.channelId(deriveStreamChannelId(speakerId, receiverId))
					.sender(speakerId)
					.opusEncodedData(opusData)
					.position(emitterPos)
					.distance(RADIO_SPEAKER_DISTANCE)
					.category(RADIO_CATEGORY_ID)
					.build();

			// 1. Deliver to the radio holder / authorized listener if not already within direct proximity of speaker
			if (receiverConn.isConnected()
					&& !receiverConn.isDisabled()
					&& !playersInDirectProximity.contains(receiverId)
					&& deliveredRecipients.add(receiverId)) {
				api.sendLocationalSoundPacketTo(receiverConn, radioSoundPacket);
				if (channelListeners.contains(receiverId)) {
					RadioNetworkManager.notifyReceivingSignal(receiverId);
				}
			}

			// 2. Spill audio 6 blocks around physical open-radio holders so nearby players also hear the radio
			if (channelListeners.contains(receiverId)) {
				Collection<ServerPlayer> bystanders = api.getPlayersInRange(
						receiverLevel,
						emitterPos,
						RADIO_SPEAKER_DISTANCE
				);
				for (ServerPlayer bystander : bystanders) {
					UUID bystanderId = bystander.getUuid();
					if (bystanderId.equals(speakerId)
							|| playersInDirectProximity.contains(bystanderId)
							|| deliveredRecipients.contains(bystanderId)) {
						continue;
					}
					VoicechatConnection bystanderConn = api.getConnectionOf(bystander);
					if (bystanderConn != null && bystanderConn.isConnected() && !bystanderConn.isDisabled()) {
						deliveredRecipients.add(bystanderId);
						api.sendLocationalSoundPacketTo(bystanderConn, radioSoundPacket);
					}
				}
			}
		}

		// 3. Emit 3D locational audio (~16 blocks) from active dropped ground radios on this channel
		for (RadioNetworkManager.DroppedRadioEntry dropped : droppedRadios) {
			ItemEntity itemEntity = dropped.entity();
			if (itemEntity == null || itemEntity.isRemoved() || !itemEntity.isAlive()) {
				RadioNetworkManager.untrackDroppedRadio(dropped.entityUuid());
				continue;
			}
			Vec3 livePos = itemEntity.position();
			ServerLevel droppedLevel = api.fromServerLevel(dropped.level());
			if (droppedLevel == null) {
				continue;
			}
			Position emitterPos = api.createPosition(
					livePos.x,
					livePos.y + DROPPED_RADIO_HEIGHT_OFFSET,
					livePos.z
			);
			LocationalSoundPacket groundPacket = micPacket.locationalSoundPacketBuilder()
					.channelId(deriveStreamChannelId(speakerId, dropped.entityUuid()))
					.sender(speakerId)
					.opusEncodedData(opusData)
					.position(emitterPos)
					.distance(DROPPED_RADIO_SPEAKER_DISTANCE)
					.category(RADIO_CATEGORY_ID)
					.build();

			boolean emittedToAnyone = false;
			Collection<ServerPlayer> nearby = api.getPlayersInRange(
					droppedLevel,
					emitterPos,
					DROPPED_RADIO_SPEAKER_DISTANCE
			);
			for (ServerPlayer listener : nearby) {
				UUID listenerId = listener.getUuid();
				// Prevent double-audio for players already receiving this channel on their own inventory radio
				if (!listenerId.equals(speakerId)
						&& (channelListeners.contains(listenerId) || deliveredRecipients.contains(listenerId))) {
					continue;
				}
				VoicechatConnection conn = api.getConnectionOf(listener);
				if (conn != null && conn.isConnected() && !conn.isDisabled()) {
					deliveredRecipients.add(listenerId);
					api.sendLocationalSoundPacketTo(conn, groundPacket);
					emittedToAnyone = true;
				}
			}
			if (emittedToAnyone) {
				RadioNetworkManager.emitDroppedRadioAudioEffect(dropped);
			}
		}
	}

	private static UUID deriveStreamChannelId(UUID speakerId, UUID emitterPlayerId) {
		ByteBuffer buffer = ByteBuffer.allocate(32);
		buffer.putLong(speakerId.getMostSignificantBits());
		buffer.putLong(speakerId.getLeastSignificantBits());
		buffer.putLong(emitterPlayerId.getMostSignificantBits());
		buffer.putLong(emitterPlayerId.getLeastSignificantBits());
		return UUID.nameUUIDFromBytes(buffer.array());
	}
}
