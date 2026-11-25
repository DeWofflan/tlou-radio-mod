package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record RadioPttPayload(boolean active) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RadioPttPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("radio_ptt"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RadioPttPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, RadioPttPayload::active,
			RadioPttPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
