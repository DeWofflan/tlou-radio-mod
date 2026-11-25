package com.tlouradio.network;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RadioChannelKey(String frequency, Optional<UUID> networkId, String passcode) {
	public RadioChannelKey {
		frequency = Objects.requireNonNullElse(frequency, "100.0").trim();
		networkId = Objects.requireNonNullElse(networkId, Optional.empty());
		passcode = Objects.requireNonNullElse(passcode, "");
	}
}
