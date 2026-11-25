package com.tlouradio.component;

import com.tlouradio.TlouRadioMod;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ModDataComponents {
	public static final DataComponentType<RadioStateComponent> RADIO_STATE = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			TlouRadioMod.id("radio_state"),
			DataComponentType.<RadioStateComponent>builder()
					.persistent(RadioStateComponent.CODEC)
					.networkSynchronized(RadioStateComponent.STREAM_CODEC)
					.cacheEncoding()
					.build()
	);

	private ModDataComponents() {
	}

	public static void initialize() {
	}
}
