package com.tlouradio.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.client.gui.HandheldRadioScreen;
import com.tlouradio.client.gui.RadioInspectionTransition;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Coordinates client-side radio controls:
 * <ul>
 *   <li>{@code X} ({@link #POWER_KEY}): single-press edge-latched Power ON/OFF toggle via {@code consumeClick()}.</li>
 *   <li>Left-Click Hold ({@code options.keyAttack.isDown()}): Push-to-Talk (PTT) strictly when {@code gui.screen() == null}
 *       and holding an active, powered, antenna-extended radio; cancels vanilla block breaking & attack swing.</li>
 *   <li>In-screen side {@code ptt_button}: independent PTT channel inside {@link HandheldRadioScreen}.</li>
 * </ul>
 */
public final class RadioClientInputHandler {
	public static final KeyMapping.Category RADIO_KEY_CATEGORY =
			KeyMapping.Category.register(TlouRadioMod.id("general"));

	public static final KeyMapping POWER_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tlouradio.power",
			InputConstants.KEY_X,
			RADIO_KEY_CATEGORY
	));

	private static final float FLICK_DURATION_SECONDS = 0.45F;

	private static boolean powerKeyLatched = false;
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
			if (player == null || player.isSpectator()) {
				return false;
			}
			boolean mainIsRadio = player.getMainHandItem().getItem() instanceof HandheldRadioItem;
			boolean offIsRadioWithEmptyMain = player.getMainHandItem().isEmpty()
					&& player.getOffhandItem().getItem() instanceof HandheldRadioItem;
			// Cancel block breaking, entity attacking, and attack swing while holding a radio
			return mainIsRadio || offIsRadioWithEmptyMain;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			handlePowerKeySinglePress(client);
			syncPttState(client);
			trackHeldRadioTransitions(client);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			powerKeyLatched = false;
			pttSentActive = false;
			screenPttActive = false;
			wasHoldingMainRadio = false;
			wasHoldingOffRadio = false;
			lastSelectedSlot = -1;
			pttRaiseProgress = 0.0F;
			RadioInspectionTransition.resetGlobalFovImmediately();
		});
	}

	private static void handlePowerKeySinglePress(Minecraft client) {
		if (!POWER_KEY.isDown()) {
			powerKeyLatched = false;
		}

		boolean hadClick = false;
		while (POWER_KEY.consumeClick()) {
			hadClick = true;
		}

		if (!hadClick || powerKeyLatched) {
			return;
		}
		if (client.gui.screen() != null || !client.mouseHandler.isMouseGrabbed()) {
			return;
		}
		if (client.player == null || !client.player.isAlive() || client.player.isSpectator()) {
			return;
		}

		ItemStack mainStack = client.player.getMainHandItem();
		if (!(mainStack.getItem() instanceof HandheldRadioItem)) {
			return;
		}

		powerKeyLatched = true;
		RadioStateComponent state = mainStack.getOrDefault(
				ModDataComponents.RADIO_STATE,
				RadioStateComponent.DEFAULT
		);
		if (!state.isPowered() && state.antennaExtended()) {
			triggerFlick(InteractionHand.MAIN_HAND);
		}
		if (ClientPlayNetworking.canSend(ToggleRadioPowerPayload.TYPE)) {
			ClientPlayNetworking.send(ToggleRadioPowerPayload.INSTANCE);
		}
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
		if (screenPttActive || pttSentActive) {
			screenPttActive = false;
			syncPttState(Minecraft.getInstance());
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

	public static void updateRenderAnimationFrame() {
		long now = System.nanoTime();
		float dt = Mth.clamp((now - lastFrameNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
		lastFrameNanos = now;

		Minecraft client = Minecraft.getInstance();
		boolean activeInWorldPtt = pttSentActive
				&& client.player != null
				&& client.gui.screen() == null;
		float targetPtt = activeInWorldPtt ? 1.0F : 0.0F;
		float speed = activeInWorldPtt ? 12.0F : 9.5F;
		pttRaiseProgress = Mth.clamp(
				pttRaiseProgress + (targetPtt - pttRaiseProgress) * Math.min(1.0F, dt * speed),
				0.0F,
				1.0F
		);
	}

	public static float getPttRaiseProgress() {
		return pttRaiseProgress;
	}

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
		Screen currentScreen = client.gui.screen();
		boolean inRadioScreen = currentScreen instanceof HandheldRadioScreen;
		if (!inRadioScreen) {
			screenPttActive = false;
		}

		// Left-click attack PTT is strictly active ONLY when no Screen is open
		boolean worldAttackPtt = (currentScreen == null)
				&& client.mouseHandler.isMouseGrabbed()
				&& client.options.keyAttack.isDown();
		boolean independentScreenPtt = inRadioScreen && screenPttActive;

		boolean wantsPtt = client.player != null
				&& client.player.isAlive()
				&& !client.player.isSpectator()
				&& (worldAttackPtt || independentScreenPtt)
				&& holdsTransmitCapableRadio(client.player);

		if (wantsPtt != pttSentActive) {
			pttSentActive = wantsPtt;
			if (ClientPlayNetworking.canSend(RadioPttPayload.TYPE)) {
				ClientPlayNetworking.send(new RadioPttPayload(wantsPtt));
			}
		}
	}

	private static boolean holdsTransmitCapableRadio(LocalPlayer player) {
		return isPoweredAndAntennaExtended(player.getMainHandItem())
				|| isPoweredAndAntennaExtended(player.getOffhandItem());
	}

	private static boolean isPoweredAndAntennaExtended(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof HandheldRadioItem)) {
			return false;
		}
		RadioStateComponent state = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		return state.isPowered() && state.antennaExtended();
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
