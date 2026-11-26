package com.tlouradio.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.client.gui.HandheldRadioScreen;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.RadioPttPayload;
import com.tlouradio.network.payload.ToggleRadioPowerPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Coordinates client-side PTT transmission state (both physical {@code X} key and in-screen 3D PTT latch)
 * as well as frame-accurate 1st-person flip-open flick and mouth-raise PTT animations.
 */
public final class RadioClientInputHandler {
	public static final KeyMapping.Category RADIO_KEY_CATEGORY =
			KeyMapping.Category.register(TlouRadioMod.id("general"));

	public static final KeyMapping PTT_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tlouradio.ptt",
			InputConstants.KEY_X,
			RADIO_KEY_CATEGORY
	));

	private static final float FLICK_DURATION_SECONDS = 0.45F;

	private static boolean pttSentActive = false;
	private static boolean screenPttActive = false;

	private static int lastSelectedSlot = -1;
	private static boolean wasHoldingMainRadio = false;
	private static boolean wasHoldingOffRadio = false;
	private static boolean lastMainRadioPowered = false;

	private static long mainFlickStartNanos = 0L;
	private static long offFlickStartNanos = 0L;
	private static float pttRaiseProgress = 0.0F;
	private static long lastFrameNanos = System.nanoTime();

	private RadioClientInputHandler() {
	}

	public static void initialize() {
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
			if (player != null && !player.isSpectator() && player.getMainHandItem().getItem() instanceof HandheldRadioItem) {
				if (clickCount > 0 && ClientPlayNetworking.canSend(ToggleRadioPowerPayload.TYPE)) {
					ItemStack mainStack = player.getMainHandItem();
					RadioStateComponent state = mainStack.getOrDefault(
							ModDataComponents.RADIO_STATE,
							RadioStateComponent.DEFAULT
					);
					// Turning radio ON via left-click triggers the tactical flip-open wrist & antenna flick
					if (!state.isPowered() && state.antennaExtended()) {
						triggerFlick(InteractionHand.MAIN_HAND);
					}
					ClientPlayNetworking.send(ToggleRadioPowerPayload.INSTANCE);
				}
				return true;
			}
			return false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			syncPttState(client);
			trackHeldRadioTransitions(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			pttSentActive = false;
			screenPttActive = false;
			wasHoldingMainRadio = false;
			wasHoldingOffRadio = false;
			lastSelectedSlot = -1;
			pttRaiseProgress = 0.0F;
		});
	}

	/**
	 * Called by {@link HandheldRadioScreen} when the side 3D {@code ptt_button} is pressed or released.
	 */
	public static void setScreenPttActive(boolean active) {
		screenPttActive = active;
		syncPttState(Minecraft.getInstance());
	}

	/**
	 * Guaranteed safe release called when mouse is released or {@link HandheldRadioScreen} closes.
	 */
	public static void releaseScreenPtt() {
		if (screenPttActive || (pttSentActive && !(Minecraft.getInstance().mouseHandler.isMouseGrabbed() && PTT_KEY.isDown()))) {
			screenPttActive = false;
			syncPttState(Minecraft.getInstance());
		} else {
			screenPttActive = false;
		}
	}

	public static boolean isPttTransmitting() {
		return pttSentActive;
	}

	public static boolean isScreenPttActive() {
		return screenPttActive;
	}

	public static void triggerFlick(InteractionHand hand) {
		long now = System.nanoTime();
		if (hand == InteractionHand.MAIN_HAND) {
			mainFlickStartNanos = now;
		} else {
			offFlickStartNanos = now;
		}
	}

	/**
	 * Updates frame-smooth 1st-person animation blend factors at render framerate.
	 */
	public static void updateRenderAnimationFrame() {
		long now = System.nanoTime();
		float dt = Mth.clamp((now - lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
		lastFrameNanos = now;

		Minecraft client = Minecraft.getInstance();
		boolean activeInWorldPtt = pttSentActive
				&& client.player != null
				&& client.gui.screen() == null;
		float targetPtt = activeInWorldPtt ? 1.0F : 0.0F;
		float speed = activeInWorldPtt ? 11.0F : 9.0F;
		pttRaiseProgress = Mth.clamp(
				pttRaiseProgress + (targetPtt - pttRaiseProgress) * Math.min(1.0F, dt * speed),
				0.0F,
				1.0F
		);
	}

	public static float getPttRaiseProgress() {
		return pttRaiseProgress;
	}

	/**
	 * Returns normalized progress {@code (0..1)} of an active flip-open flick for {@code hand}, or {@code 0} if idle.
	 */
	public static float getFlickProgress(InteractionHand hand) {
		long start = (hand == InteractionHand.MAIN_HAND) ? mainFlickStartNanos : offFlickStartNanos;
		if (start == 0L) {
			return 0.0F;
		}
		float elapsed = (System.nanoTime() - start) / 1_000_000_000.0F;
		if (elapsed < 0.0F || elapsed >= FLICK_DURATION_SECONDS) {
			return 0.0F;
		}
		return elapsed / FLICK_DURATION_SECONDS;
	}

	private static void syncPttState(Minecraft client) {
		if (client == null) {
			return;
		}
		boolean inRadioScreen = client.gui.screen() instanceof HandheldRadioScreen;
		if (!inRadioScreen) {
			screenPttActive = false;
		}

		boolean allowPttInUi = client.mouseHandler.isMouseGrabbed() || inRadioScreen;
		boolean triggerHeld = PTT_KEY.isDown() || (inRadioScreen && screenPttActive);
		boolean wantsPtt = client.player != null
				&& client.player.isAlive()
				&& !client.player.isSpectator()
				&& allowPttInUi
				&& triggerHeld
				&& holdsAnyRadio(client.player);

		if (wantsPtt != pttSentActive) {
			pttSentActive = wantsPtt;
			if (ClientPlayNetworking.canSend(RadioPttPayload.TYPE)) {
				ClientPlayNetworking.send(new RadioPttPayload(wantsPtt));
			}
		}
	}

	private static boolean holdsAnyRadio(net.minecraft.client.player.LocalPlayer player) {
		return player.getMainHandItem().getItem() instanceof HandheldRadioItem
				|| player.getOffhandItem().getItem() instanceof HandheldRadioItem;
	}

	private static void trackHeldRadioTransitions(Minecraft client) {
		if (client.player == null) {
			wasHoldingMainRadio = false;
			wasHoldingOffRadio = false;
			lastSelectedSlot = -1;
			return;
		}

		int currentSlot = client.player.getInventory().getSelectedSlot();
		ItemStack mainStack = client.player.getMainHandItem();
		ItemStack offStack = client.player.getOffhandItem();

		boolean holdingMain = mainStack.getItem() instanceof HandheldRadioItem;
		boolean holdingOff = offStack.getItem() instanceof HandheldRadioItem;

		if (holdingMain) {
			RadioStateComponent state = mainStack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			if ((!wasHoldingMainRadio || currentSlot != lastSelectedSlot) && state.antennaExtended()) {
				triggerFlick(InteractionHand.MAIN_HAND);
			} else if (!lastMainRadioPowered && state.isPowered() && state.antennaExtended()) {
				triggerFlick(InteractionHand.MAIN_HAND);
			}
			lastMainRadioPowered = state.isPowered();
		} else {
			lastMainRadioPowered = false;
		}

		if (holdingOff && !wasHoldingOffRadio) {
			RadioStateComponent offState = offStack.getOrDefault(
					ModDataComponents.RADIO_STATE,
					RadioStateComponent.DEFAULT
			);
			if (offState.antennaExtended()) {
				triggerFlick(InteractionHand.OFF_HAND);
			}
		}

		wasHoldingMainRadio = holdingMain;
		wasHoldingOffRadio = holdingOff;
		lastSelectedSlot = currentSlot;
	}
}
