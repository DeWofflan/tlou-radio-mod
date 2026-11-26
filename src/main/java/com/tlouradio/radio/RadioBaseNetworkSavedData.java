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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Overworld-backed persistent O(1) registry of all active Radio Base Boxes and their hosted frequencies.
 * Supports up to {@link #MAX_CHANNELS_PER_BOX} channels per Base Box and backward-compatible loading
 * of legacy single-channel entries.
 */
public final class RadioBaseNetworkSavedData extends SavedData {
	public static final int MAX_CHANNELS_PER_BOX = 8;

	public record FrequencyOwner(UUID networkId, String passcode) {
		public FrequencyOwner {
			Objects.requireNonNull(networkId, "networkId");
			passcode = Objects.requireNonNullElse(passcode, "");
		}
	}

	public record BaseNetworkEntry(
			UUID networkId,
			List<RadioBaseChannel> channels,
			Optional<GlobalPos> pos
	) {
		public static final Codec<BaseNetworkEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				UUIDUtil.CODEC.fieldOf("network_id").forGetter(BaseNetworkEntry::networkId),
				RadioBaseChannel.CODEC.listOf().optionalFieldOf("channels", List.of()).forGetter(BaseNetworkEntry::channels),
				Codec.STRING.optionalFieldOf("frequency").forGetter(entry -> Optional.empty()),
				Codec.STRING.optionalFieldOf("passcode").forGetter(entry -> Optional.empty()),
				GlobalPos.CODEC.optionalFieldOf("pos").forGetter(BaseNetworkEntry::pos)
		).apply(instance, BaseNetworkEntry::fromCodec));

		private static BaseNetworkEntry fromCodec(
				UUID networkId,
				List<RadioBaseChannel> channels,
				Optional<String> legacyFrequency,
				Optional<String> legacyPasscode,
				Optional<GlobalPos> pos
		) {
			if (channels != null && !channels.isEmpty()) {
				return new BaseNetworkEntry(networkId, channels, pos);
			}
			String freq = legacyFrequency.orElse(RadioStateComponent.DEFAULT_FREQUENCY);
			String pass = legacyPasscode.orElse("");
			return new BaseNetworkEntry(networkId, List.of(new RadioBaseChannel(freq, pass)), pos);
		}

		public BaseNetworkEntry {
			Objects.requireNonNull(networkId, "networkId");
			List<RadioBaseChannel> sanitized = sanitizeChannels(channels);
			channels = sanitized.isEmpty() ? List.of(RadioBaseChannel.DEFAULT) : sanitized;
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
	private final Map<String, FrequencyOwner> frequencyIndex = new HashMap<>();

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

	public static List<RadioBaseChannel> sanitizeChannels(List<RadioBaseChannel> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		Map<String, RadioBaseChannel> dedup = new LinkedHashMap<>();
		for (RadioBaseChannel ch : raw) {
			if (ch == null) {
				continue;
			}
			if (dedup.size() >= MAX_CHANNELS_PER_BOX) {
				break;
			}
			String normFreq = RadioStateComponent.normalizeFrequency(ch.frequency());
			dedup.putIfAbsent(normFreq, new RadioBaseChannel(normFreq, ch.passcode()));
		}
		return List.copyOf(dedup.values());
	}

	private List<BaseNetworkEntry> entriesForSerialization() {
		return new ArrayList<>(this.networks.values());
	}

	private void removeFrequenciesOf(BaseNetworkEntry entry) {
		if (entry == null) {
			return;
		}
		for (RadioBaseChannel ch : entry.channels()) {
			String normFreq = RadioStateComponent.normalizeFrequency(ch.frequency());
			FrequencyOwner existing = this.frequencyIndex.get(normFreq);
			if (existing != null && existing.networkId().equals(entry.networkId())) {
				this.frequencyIndex.remove(normFreq);
			}
		}
	}

	private void putInternal(BaseNetworkEntry entry) {
		BaseNetworkEntry previous = this.networks.put(entry.networkId(), entry);
		if (previous != null) {
			removeFrequenciesOf(previous);
			if (previous.pos().isPresent()) {
				this.positionIndex.remove(previous.pos().get(), entry.networkId());
			}
		}
		entry.pos().ifPresent(globalPos -> {
			UUID previousAtPos = this.positionIndex.put(globalPos, entry.networkId());
			if (previousAtPos != null && !previousAtPos.equals(entry.networkId())) {
				BaseNetworkEntry displaced = this.networks.remove(previousAtPos);
				removeFrequenciesOf(displaced);
			}
		});
		for (RadioBaseChannel ch : entry.channels()) {
			String normFreq = RadioStateComponent.normalizeFrequency(ch.frequency());
			this.frequencyIndex.put(normFreq, new FrequencyOwner(entry.networkId(), ch.passcode()));
		}
	}

	public Optional<BaseNetworkEntry> getEntry(UUID networkId) {
		if (networkId == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.networks.get(networkId));
	}

	/**
	 * O(1) lookup of the Base Box owning {@code frequency}, if any.
	 */
	public Optional<FrequencyOwner> lookupFrequency(String frequency) {
		if (frequency == null || frequency.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(this.frequencyIndex.get(RadioStateComponent.normalizeFrequency(frequency)));
	}

	/**
	 * Checks whether any channel in {@code channels} is already claimed by a different Base Box.
	 */
	public Optional<String> findConflictingFrequency(UUID ownerNetworkId, List<RadioBaseChannel> channels) {
		if (channels == null) {
			return Optional.empty();
		}
		for (RadioBaseChannel ch : channels) {
			if (ch == null) {
				continue;
			}
			String normFreq = RadioStateComponent.normalizeFrequency(ch.frequency());
			FrequencyOwner existing = this.frequencyIndex.get(normFreq);
			if (existing != null && !existing.networkId().equals(ownerNetworkId)) {
				return Optional.of(normFreq);
			}
		}
		return Optional.empty();
	}

	/**
	 * Finds the first unclaimed frequency starting at {@code preferredFrequency}, stepping +1.0 MHz
	 * (and falling back to +0.1 MHz steps if all whole numbers are occupied).
	 */
	public String findFirstAvailableFrequency(String preferredFrequency, UUID ownerNetworkId, java.util.Set<String> extraReserved) {
		String preferred = RadioStateComponent.normalizeFrequency(preferredFrequency);
		if (isFrequencyAvailableFor(preferred, ownerNetworkId, extraReserved)) {
			return preferred;
		}

		int startWhole = 100;
		try {
			double parsed = Double.parseDouble(preferred);
			if (Double.isFinite(parsed)) {
				startWhole = (int) Math.clamp(Math.floor(parsed), 1L, 999L);
			}
		} catch (NumberFormatException ignored) {
		}

		// Try whole MHz increments first: e.g. 101.0, 102.0, ..., 999.0, then 1.0..99.0
		for (int offset = 1; offset <= 999; offset++) {
			int whole = ((startWhole - 1 + offset) % 999) + 1;
			String candidate = String.format(Locale.ROOT, "%d.0", whole);
			if (isFrequencyAvailableFor(candidate, ownerNetworkId, extraReserved)) {
				return candidate;
			}
		}

		// Fallback: 0.1 MHz increments across 1.0..999.9
		for (int tenths = RadioStateComponent.MIN_DIAL_TENTHS; tenths <= RadioStateComponent.MAX_DIAL_TENTHS; tenths++) {
			String candidate = String.format(Locale.ROOT, "%d.%d", tenths / 10, tenths % 10);
			if (isFrequencyAvailableFor(candidate, ownerNetworkId, extraReserved)) {
				return candidate;
			}
		}

		return preferred;
	}

	private boolean isFrequencyAvailableFor(String normalizedFreq, UUID ownerNetworkId, java.util.Set<String> extraReserved) {
		if (extraReserved != null && extraReserved.contains(normalizedFreq)) {
			return false;
		}
		FrequencyOwner owner = this.frequencyIndex.get(normalizedFreq);
		return owner == null || owner.networkId().equals(ownerNetworkId);
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

	public void registerOrUpdate(UUID networkId, List<RadioBaseChannel> channels, GlobalPos pos) {
		Objects.requireNonNull(networkId, "networkId");
		BaseNetworkEntry updated = new BaseNetworkEntry(
				networkId,
				channels,
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
		removeFrequenciesOf(removed);
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
		BaseNetworkEntry removed = this.networks.remove(networkId);
		removeFrequenciesOf(removed);
		setDirty();
		return Optional.of(networkId);
	}

	/**
	 * O(1) validation for both paired and open-frequency radios.
	 * <ul>
	 *   <li>If a frequency is claimed by a Base Box, ONLY radios paired to that exact Base Box with matching passcode are valid.</li>
	 *   <li>If a frequency is unclaimed, open-frequency radios ({@code networkId.isEmpty()}) are valid.</li>
	 * </ul>
	 */
	public boolean isValidChannel(RadioChannelKey channel) {
		if (channel == null) {
			return false;
		}
		String normFreq = RadioStateComponent.normalizeFrequency(channel.frequency());
		FrequencyOwner owner = this.frequencyIndex.get(normFreq);
		if (channel.networkId().isEmpty()) {
			return owner == null;
		}
		if (owner == null) {
			return false;
		}
		return owner.networkId().equals(channel.networkId().get())
				&& owner.passcode().equals(channel.passcode());
	}
}
