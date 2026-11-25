package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record UpdateBaseBoxConfigPayload(
		BlockPos pos,
		String frequency,
		String passcode
) implements CustomPacketPayload {
	private static final int MAX_WIRE_STRING_LENGTH = 16;

	public static final CustomPacketPayload.Type<UpdateBaseBoxConfigPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("update_base_box_config"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBaseBoxConfigPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, UpdateBaseBoxConfigPayload::pos,
			ByteBufCodecs.stringUtf8(MAX_WIRE_STRING_LENGTH), UpdateBaseBoxConfigPayload::frequency,
			ByteBufCodecs.stringUtf8(MAX_WIRE_STRING_LENGTH), UpdateBaseBoxConfigPayload::passcode,
			UpdateBaseBoxConfigPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
