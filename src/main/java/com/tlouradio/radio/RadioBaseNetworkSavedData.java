package com.tlouradio.radio;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.network.RadioChannelKey;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Overworld-backed persistent O(1) registry of all active Radio Base Boxes.
 * Allows validating (networkId, frequency, passcode) without loading chunks or running server ticks.
 */
public final class RadioBaseNetworkSavedData extends SavedData {
	public record BaseNetworkEntry(
			UUID networkId,
			String frequency,
			String passcode,
			Optional<GlobalPos> pos
	) {
		public static final Codec<BaseNetworkEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("network_id").forGetter(BaseNetworkEntry::networkId),
				Codec.STRING.optionalFieldOf("frequency", RadioStateComponent.DEFAULT_FREQUENCY).forGetter(BaseNetworkEntry::frequency),
				Codec.STRING.optionalFieldOf("passcode", "").forGetter(BaseNetworkEntry::passcode),
				GlobalPos.CODEC.optionalFieldOf("pos").forGetter(BaseNetworkEntry::pos)
		).apply(instance, BaseNetworkEntry::new));

		public BaseNetworkEntry {
			Objects.requireNonNull(networkId, "networkId");
			frequency = Objects.requireNonNullElse(frequency, RadioStateComponent.DEFAULT_FREQUENCY).trim();
			if (frequency.isEmpty()) {
				frequency = RadioStateComponent.DEFAULT_FREQUENCY;
			}
			passcode = Objects.requireNonNullElse(passcode, "");
			pos = Objects.requireNonNullElse(pos, Optional.empty());
		}
	}

	public static final Codec<RadioBaseNetworkSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BaseNetworkEntry.CODEC.listOf()
					.optionalFieldOf("networks", List.of())
					.forGetter(RadioBaseNetworkSavedData::entriesForSerialization)
	).apply(instance, RadioBaseNetworkSavedData::fromEntries));

	public static final SavedDataType<RadioBaseNetworkSavedData> TYPE = new SavedDataType<>(
			TlouRadioMod.id("radio_base_networks"),
			RadioBaseNetworkSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	private final Map<UUID, BaseNetworkEntry> networks = new HashMap<>();
	private final Map<GlobalPos, UUID> positionIndex = new HashMap<>();

	public RadioBaseNetworkSavedData() {
	}

	private static RadioBaseNetworkSavedData fromEntries(List<BaseNetworkEntry> entries) {
		RadioBaseNetworkSavedData data = new RadioBaseNetworkSavedData();
		for (BaseNetworkEntry entry : entries) {
			data.putInternal(entry);
		}
		return data;
	}

	public static RadioBaseNetworkSavedData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	private List<BaseNetworkEntry> entriesForSerialization() {
		return new ArrayList<>(this.networks.values());
	}

	private void putInternal(BaseNetworkEntry entry) {
		BaseNetworkEntry previous = this.networks.put(entry.networkId(), entry);
		if (previous != null && previous.pos().isPresent()) {
			this.positionIndex.remove(previous.pos().get(), entry.networkId());
		}
		entry.pos().ifPresent(globalPos -> {
			UUID previousAtPos = this.positionIndex.put(globalPos, entry.networkId());
			if (previousAtPos != null && !previousAtPos.equals(entry.networkId())) {
				this.networks.remove(previousAtPos);
			}
		});
	}

	public Optional<BaseNetworkEntry> getEntry(UUID networkId) {
		if (networkId == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.networks.get(networkId));
	}

	/**
	 * Returns true if {@code networkId} is already registered at a different {@link GlobalPos}
	 * (e.g., Creative Ctrl+Middle-Click block copy).
	 */
	public boolean isRegisteredAtDifferentPos(UUID networkId, GlobalPos targetPos) {
		if (networkId == null || targetPos == null) {
			return false;
		}
		BaseNetworkEntry existing = this.networks.get(networkId);
		if (existing == null || existing.pos().isEmpty()) {
			return false;
		}
		return !existing.pos().get().equals(targetPos);
	}

	public void registerOrUpdate(UUID networkId, String frequency, String passcode, GlobalPos pos) {
		Objects.requireNonNull(networkId, "networkId");
		BaseNetworkEntry updated = new BaseNetworkEntry(
				networkId,
				frequency,
				passcode,
				Optional.ofNullable(pos)
		);
		BaseNetworkEntry current = this.networks.get(networkId);
		if (updated.equals(current)) {
			return;
		}
		putInternal(updated);
		setDirty();
	}

	public boolean unregister(UUID networkId) {
		if (networkId == null) {
			return false;
		}
		BaseNetworkEntry removed = this.networks.remove(networkId);
		if (removed == null) {
			return false;
		}
		removed.pos().ifPresent(pos -> this.positionIndex.remove(pos, networkId));
		setDirty();
		return true;
	}

	public Optional<UUID> unregisterByPosition(GlobalPos pos) {
		if (pos == null) {
			return Optional.empty();
		}
		UUID networkId = this.positionIndex.remove(pos);
		if (networkId == null) {
			return Optional.empty();
		}
		this.networks.remove(networkId);
		setDirty();
		return Optional.of(networkId);
	}

	/**
	 * O(1) validation for paired radios. Open-frequency radios (empty networkId) are always allowed.
	 */
	public boolean isValidChannel(RadioChannelKey channel) {
		if (channel == null) {
			return false;
		}
		if (channel.networkId().isEmpty()) {
			return true;
		}
		BaseNetworkEntry entry = this.networks.get(channel.networkId().get());
		if (entry == null) {
			return false;
		}
		return entry.frequency().equals(channel.frequency())
				&& entry.passcode().equals(channel.passcode());
	}
}
