package com.tlouradio.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.RadioPttPayload;
import com.tlouradio.network.payload.ToggleRadioPowerPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.KeyMapping;

public final class RadioClientInputHandler {
	public static final KeyMapping.Category RADIO_KEY_CATEGORY =
			KeyMapping.Category.register(TlouRadioMod.id("general"));

	public static final KeyMapping PTT_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.tlouradio.ptt",
			InputConstants.KEY_X,
			RADIO_KEY_CATEGORY
	));

	private static boolean pttSentActive = false;

	private RadioClientInputHandler() {
	}

	public static void initialize() {
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
			if (player != null && !player.isSpectator() && player.getMainHandItem().getItem() instanceof HandheldRadioItem) {
				if (clickCount > 0 && ClientPlayNetworking.canSend(ToggleRadioPowerPayload.TYPE)) {
					ClientPlayNetworking.send(ToggleRadioPowerPayload.INSTANCE);
				}
				return true;
			}
			return false;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean wantsPtt = client.player != null
					&& client.mouseHandler.isMouseGrabbed()
					&& !client.player.isSpectator()
					&& PTT_KEY.isDown();

			if (wantsPtt != pttSentActive) {
				pttSentActive = wantsPtt;
				if (ClientPlayNetworking.canSend(RadioPttPayload.TYPE)) {
					ClientPlayNetworking.send(new RadioPttPayload(wantsPtt));
				}
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pttSentActive = false);
	}
}
