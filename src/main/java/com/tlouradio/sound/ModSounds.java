package com.tlouradio.sound;

import com.tlouradio.TlouRadioMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {
	public static final SoundEvent RADIO_POWER_ON = register("radio_power_on");
	public static final SoundEvent RADIO_POWER_OFF = register("radio_power_off");
	public static final SoundEvent RADIO_PTT_START = register("radio_ptt_start");
	public static final SoundEvent RADIO_PTT_END = register("radio_ptt_end");
	public static final SoundEvent RADIO_SABOTAGE = register("radio_sabotage");

	private ModSounds() {
	}

	private static SoundEvent register(String path) {
		Identifier id = TlouRadioMod.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	public static void initialize() {
	}
}
