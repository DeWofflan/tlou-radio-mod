package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Lightweight rate-limited S2C pulse indicating that the local player's radio is actively receiving audio.
 */
public final class RadioReceivingSignalPayload implements CustomPacketPayload {
	public static final RadioReceivingSignalPayload INSTANCE = new RadioReceivingSignalPayload();

	public static final CustomPacketPayload.Type<RadioReceivingSignalPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("radio_receiving_signal"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RadioReceivingSignalPayload> STREAM_CODEC =
			StreamCodec.unit(INSTANCE);

	private RadioReceivingSignalPayload() {
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
