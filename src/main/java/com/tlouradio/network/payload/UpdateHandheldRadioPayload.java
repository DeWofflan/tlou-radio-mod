package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

public record UpdateHandheldRadioPayload(
		String frequency,
		String passcode,
		boolean antennaExtended,
		InteractionHand hand
) implements CustomPacketPayload {
	private static final int MAX_WIRE_STRING_LENGTH = 16;

	public static final CustomPacketPayload.Type<UpdateHandheldRadioPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("update_handheld_radio"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UpdateHandheldRadioPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_WIRE_STRING_LENGTH), UpdateHandheldRadioPayload::frequency,
			ByteBufCodecs.stringUtf8(MAX_WIRE_STRING_LENGTH), UpdateHandheldRadioPayload::passcode,
			ByteBufCodecs.BOOL, UpdateHandheldRadioPayload::antennaExtended,
			InteractionHand.STREAM_CODEC, UpdateHandheldRadioPayload::hand,
			UpdateHandheldRadioPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
