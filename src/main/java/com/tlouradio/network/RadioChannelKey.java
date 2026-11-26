package com.tlouradio.network;

import com.tlouradio.component.RadioStateComponent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RadioChannelKey(String frequency, Optional<UUID> networkId, String passcode) {
	public RadioChannelKey {
		frequency = RadioStateComponent.normalizeFrequency(frequency);
		networkId = Objects.requireNonNullElse(networkId, Optional.empty());
		passcode = Objects.requireNonNullElse(passcode, "");
	}
}
