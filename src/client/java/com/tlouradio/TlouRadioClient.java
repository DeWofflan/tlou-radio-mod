package com.tlouradio;

import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.gui.RadioBaseBoxScreen;
import com.tlouradio.network.payload.OpenBaseBoxScreenPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class TlouRadioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RadioClientInputHandler.initialize();
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
