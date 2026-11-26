package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.radio.RadioBaseChannel;
import com.tlouradio.radio.RadioBaseNetworkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

public record UpdateBaseBoxConfigPayload(
		BlockPos pos,
		List<RadioBaseChannel> channels,
		int selectedChannelIndex
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<UpdateBaseBoxConfigPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("update_base_box_config"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBaseBoxConfigPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, UpdateBaseBoxConfigPayload::pos,
			RadioBaseChannel.STREAM_CODEC.apply(ByteBufCodecs.list(RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX)), UpdateBaseBoxConfigPayload::channels,
			ByteBufCodecs.VAR_INT, UpdateBaseBoxConfigPayload::selectedChannelIndex,
			UpdateBaseBoxConfigPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
