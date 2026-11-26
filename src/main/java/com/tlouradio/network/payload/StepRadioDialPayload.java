package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

public record StepRadioDialPayload(
		String frequency,
		InteractionHand hand
) implements CustomPacketPayload {
	private static final int MAX_WIRE_STRING_LENGTH = 16;

	public static final CustomPacketPayload.Type<StepRadioDialPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("step_radio_dial"));

	public static final StreamCodec<RegistryFriendlyByteBuf, StepRadioDialPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_WIRE_STRING_LENGTH), StepRadioDialPayload::frequency,
			InteractionHand.STREAM_CODEC, StepRadioDialPayload::hand,
			StepRadioDialPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
