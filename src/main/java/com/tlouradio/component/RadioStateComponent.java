package com.tlouradio.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tlouradio.network.RadioChannelKey;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RadioStateComponent(
		boolean isPowered,
		String frequency,
		Optional<UUID> networkId,
		String passcode
) {
	public static final String DEFAULT_FREQUENCY = "100.0";
	public static final int MAX_FREQUENCY_LENGTH = 8;
	public static final int MAX_PASSCODE_LENGTH = 12;

	public static final RadioStateComponent DEFAULT = new RadioStateComponent(
			false,
			DEFAULT_FREQUENCY,
			Optional.empty(),
			""
	);

	public static final Codec<RadioStateComponent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("is_powered", false).forGetter(RadioStateComponent::isPowered),
			Codec.STRING.optionalFieldOf("frequency", DEFAULT_FREQUENCY).forGetter(RadioStateComponent::frequency),
			UUIDUtil.CODEC.optionalFieldOf("network_id").forGetter(RadioStateComponent::networkId),
			Codec.STRING.optionalFieldOf("passcode", "").forGetter(RadioStateComponent::passcode)
	).apply(instance, RadioStateComponent::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, RadioStateComponent> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RadioStateComponent::isPowered,
			ByteBufCodecs.STRING_UTF8, RadioStateComponent::frequency,
			ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), RadioStateComponent::networkId,
			ByteBufCodecs.STRING_UTF8, RadioStateComponent::passcode,
			RadioStateComponent::new
	);

	public RadioStateComponent {
		frequency = Objects.requireNonNullElse(frequency, DEFAULT_FREQUENCY).trim();
		if (frequency.isEmpty()) {
			frequency = DEFAULT_FREQUENCY;
		}
		networkId = Objects.requireNonNullElse(networkId, Optional.empty());
		passcode = Objects.requireNonNullElse(passcode, "");
	}

	public RadioStateComponent withPowered(boolean powered) {
		return new RadioStateComponent(powered, this.frequency, this.networkId, this.passcode);
	}

	public RadioStateComponent withFrequency(String newFrequency) {
		return new RadioStateComponent(this.isPowered, newFrequency, this.networkId, this.passcode);
	}

	public RadioStateComponent withNetworkId(Optional<UUID> newNetworkId) {
		return new RadioStateComponent(this.isPowered, this.frequency, newNetworkId, this.passcode);
	}

	public RadioStateComponent withPasscode(String newPasscode) {
		return new RadioStateComponent(this.isPowered, this.frequency, this.networkId, newPasscode);
	}

	public RadioStateComponent withPairedNetwork(UUID pairedNetworkId, String newFrequency, String newPasscode) {
		return new RadioStateComponent(
				this.isPowered,
				newFrequency,
				Optional.of(Objects.requireNonNull(pairedNetworkId, "pairedNetworkId")),
				newPasscode
		);
	}

	public RadioChannelKey toChannelKey() {
		return new RadioChannelKey(this.frequency, this.networkId, this.passcode);
	}

	/**
	 * Validates keypad-compatible frequency: digits 0-9 and at most one '.', length 1..8,
	 * at least one digit, and no leading/trailing '.'.
	 */
	public static boolean isValidFrequency(String frequency) {
		if (frequency == null || frequency.isEmpty() || frequency.length() > MAX_FREQUENCY_LENGTH) {
			return false;
		}
		if (frequency.charAt(0) == '.' || frequency.charAt(frequency.length() - 1) == '.') {
			return false;
		}
		int dotCount = 0;
		int digitCount = 0;
		for (int i = 0; i < frequency.length(); i++) {
			char c = frequency.charAt(i);
			if (c >= '0' && c <= '9') {
				digitCount++;
			} else if (c == '.') {
				dotCount++;
				if (dotCount > 1) {
					return false;
				}
			} else {
				return false;
			}
		}
		return digitCount > 0;
	}

	/**
	 * Validates keypad-compatible passcode: digits 0-9 only, length 0..12 (empty allowed).
	 */
	public static boolean isValidPasscode(String passcode) {
		if (passcode == null || passcode.length() > MAX_PASSCODE_LENGTH) {
			return false;
		}
		for (int i = 0; i < passcode.length(); i++) {
			char c = passcode.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	public static String shortNetworkId(UUID id) {
		if (id == null) {
			return "--------";
		}
		return id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}
}
