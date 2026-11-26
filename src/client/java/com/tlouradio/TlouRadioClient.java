package com.tlouradio;

import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.gui.HandheldRadioScreen;
import com.tlouradio.client.gui.RadioBaseBoxScreen;
import com.tlouradio.client.gui.render.HandheldRadioModelRenderer;
import com.tlouradio.network.payload.OpenBaseBoxScreenPayload;
import com.tlouradio.radio.RadioClientHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class TlouRadioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HandheldRadioModelRenderer.initialize();
		RadioClientInputHandler.initialize();
		RadioClientHooks.registerHandheldScreenOpener((hand, stack) ->
				Minecraft.getInstance().gui.setScreen(new HandheldRadioScreen(hand, stack))
		);
		registerClientNetworking();
		TlouRadioMod.LOGGER.info("TLOU2 Radio istemci katmani baslatildi.");
	}

	private static void registerClientNetworking() {
		ClientPlayNetworking.registerGlobalReceiver(OpenBaseBoxScreenPayload.TYPE, (payload, context) ->
				context.client().gui.setScreen(new RadioBaseBoxScreen(
						payload.pos(),
						payload.networkId(),
						payload.frequency(),
						payload.passcode()
				))
		);
	}
}
