package com.tlouradio;

import net.fabricmc.api.ClientModInitializer;

public class TlouRadioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		TlouRadioMod.LOGGER.info("TLOU2 Radio istemci katmani baslatildi.");
	}
}
