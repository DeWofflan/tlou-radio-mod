package com.tlouradio.client;

import net.minecraft.util.Mth;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight client-side cache for:
 * <ul>
 *   <li>Remote/local players currently transmitting on PTT (for 3rd-person spyglass arm pose).</li>
 *   <li>Timestamp of the most recent incoming radio audio pulse (for RX micro-vibration & LED/LCD pulse).</li>
 * </ul>
 */
public final class RadioClientState {
	private static final long RX_ACTIVE_WINDOW_MS = 320L;

	private static final Set<UUID> ACTIVE_TRANSMITTERS = ConcurrentHashMap.newKeySet();
	private static volatile long lastRxSignalMs = 0L;

	private RadioClientState() {
	}

	public static void replaceAllTransmitters(Collection<UUID> activePlayerIds) {
		ACTIVE_TRANSMITTERS.clear();
		if (activePlayerIds != null) {
			ACTIVE_TRANSMITTERS.addAll(activePlayerIds);
		}
	}

	public static void setTransmitting(UUID playerId, boolean transmitting) {
		if (playerId == null) {
			return;
		}
		if (transmitting) {
			ACTIVE_TRANSMITTERS.add(playerId);
		} else {
			ACTIVE_TRANSMITTERS.remove(playerId);
		}
	}

	public static boolean isTransmitting(UUID playerId) {
		return playerId != null && ACTIVE_TRANSMITTERS.contains(playerId);
	}

	public static void markReceivingSignal() {
		lastRxSignalMs = System.currentTimeMillis();
	}

	/**
	 * Returns 0.0 when idle, or a smoothly decaying (0..1] intensity while incoming audio is active.
	 */
	public static float getRxIntensity() {
		long last = lastRxSignalMs;
		if (last == 0L) {
			return 0.0F;
		}
		long elapsed = System.currentTimeMillis() - last;
		if (elapsed < 0L || elapsed >= RX_ACTIVE_WINDOW_MS) {
			return 0.0F;
		}
		float linear = 1.0F - ((float) elapsed / (float) RX_ACTIVE_WINDOW_MS);
		return Mth.clamp(linear * linear, 0.0F, 1.0F);
	}

	public static boolean isReceivingAudio() {
		return getRxIntensity() > 0.02F;
	}

	public static void clear() {
		ACTIVE_TRANSMITTERS.clear();
		lastRxSignalMs = 0L;
	}
}
