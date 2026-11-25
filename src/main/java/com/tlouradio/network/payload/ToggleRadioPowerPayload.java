package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleRadioPowerPayload() implements CustomPacketPayload {
	public static final ToggleRadioPowerPayload INSTANCE = new ToggleRadioPowerPayload();

	public static final CustomPacketPayload.Type<ToggleRadioPowerPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("toggle_radio_power"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ToggleRadioPowerPayload> STREAM_CODEC =
			StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
