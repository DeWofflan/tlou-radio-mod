package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

public record SetRadioAntennaPayload(
		boolean antennaExtended,
		InteractionHand hand
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SetRadioAntennaPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("set_radio_antenna"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SetRadioAntennaPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, SetRadioAntennaPayload::antennaExtended,
			InteractionHand.STREAM_CODEC, SetRadioAntennaPayload::hand,
			SetRadioAntennaPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
