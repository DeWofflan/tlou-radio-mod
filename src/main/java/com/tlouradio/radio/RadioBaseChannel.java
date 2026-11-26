package com.tlouradio.radio;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tlouradio.component.RadioStateComponent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Objects;

/**
 * Single (frequency, passcode) channel hosted by a {@code RadioBaseBoxBlockEntity}.
 */
public record RadioBaseChannel(
		String frequency,
		String passcode
) {
	public static final RadioBaseChannel DEFAULT = new RadioBaseChannel(RadioStateComponent.DEFAULT_FREQUENCY, "");

	public static final Codec<RadioBaseChannel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("frequency", RadioStateComponent.DEFAULT_FREQUENCY).forGetter(RadioBaseChannel::frequency),
			Codec.STRING.optionalFieldOf("passcode", "").forGetter(RadioBaseChannel::passcode)
	).apply(instance, RadioBaseChannel::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, RadioBaseChannel> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(16), RadioBaseChannel::frequency,
			ByteBufCodecs.stringUtf8(16), RadioBaseChannel::passcode,
			RadioBaseChannel::new
	);

	public RadioBaseChannel {
		String rawFreq = Objects.requireNonNullElse(frequency, RadioStateComponent.DEFAULT_FREQUENCY).trim();
		frequency = RadioStateComponent.isValidFrequency(rawFreq)
				? RadioStateComponent.normalizeFrequency(rawFreq)
				: RadioStateComponent.DEFAULT_FREQUENCY;
		String rawPass = Objects.requireNonNullElse(passcode, "");
		passcode = RadioStateComponent.isValidPasscode(rawPass) ? rawPass : "";
	}
}
