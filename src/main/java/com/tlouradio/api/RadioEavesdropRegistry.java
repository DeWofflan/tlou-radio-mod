package com.tlouradio.api;

import com.tlouradio.network.RadioChannelKey;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RadioEavesdropRegistry {
	private static final List<RadioEavesdropHook> HOOKS = new CopyOnWriteArrayList<>();

	private RadioEavesdropRegistry() {
	}

	public static void register(RadioEavesdropHook hook) {
		HOOKS.add(Objects.requireNonNull(hook, "hook"));
	}

	public static void unregister(RadioEavesdropHook hook) {
		HOOKS.remove(hook);
	}

	public static boolean hasHooks() {
		return !HOOKS.isEmpty();
	}

	public static boolean isAuthorized(UUID listenerId, UUID speakerId, RadioChannelKey channel) {
		for (RadioEavesdropHook hook : HOOKS) {
			if (hook.canEavesdrop(listenerId, speakerId, channel)) {
				return true;
			}
		}
		return false;
	}
}
