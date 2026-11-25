package com.tlouradio.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tlouradio.network.RadioChannelKey;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

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

	public RadioChannelKey toChannelKey() {
		return new RadioChannelKey(this.frequency, this.networkId, this.passcode);
	}
}
