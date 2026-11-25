package com.tlouradio.compat;

import com.tlouradio.TlouRadioMod;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;

public class TlouRadioVoicechatPlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return TlouRadioMod.MOD_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		TlouRadioMod.LOGGER.info("TLOU2 Radio - Simple Voice Chat eklentisi calisma zamaninda basariyla baglandi.");
	}
}
