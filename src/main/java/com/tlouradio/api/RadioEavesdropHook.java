package com.tlouradio.api;

import com.tlouradio.network.RadioChannelKey;

import java.util.UUID;

@FunctionalInterface
public interface RadioEavesdropHook {
	/**
	 * Called asynchronously on the Simple Voice Chat UDP packet processing thread.
	 * Implementations MUST be thread-safe and MUST NOT access non-thread-safe
	 * Minecraft main-thread state (such as ServerPlayer inventories or Levels).
	 */
	boolean canEavesdrop(UUID listenerId, UUID speakerId, RadioChannelKey channel);
}
