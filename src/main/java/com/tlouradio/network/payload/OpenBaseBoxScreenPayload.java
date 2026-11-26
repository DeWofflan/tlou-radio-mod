package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.radio.RadioBaseChannel;
import com.tlouradio.radio.RadioBaseNetworkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

public record OpenBaseBoxScreenPayload(
		BlockPos pos,
		UUID networkId,
		List<RadioBaseChannel> channels,
		int selectedChannelIndex
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<OpenBaseBoxScreenPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("open_base_box_screen"));

	public static final StreamCodec<RegistryFriendlyByteBuf, OpenBaseBoxScreenPayload> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, OpenBaseBoxScreenPayload::pos,
			UUIDUtil.STREAM_CODEC, OpenBaseBoxScreenPayload::networkId,
			RadioBaseChannel.STREAM_CODEC.apply(ByteBufCodecs.list(RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX)), OpenBaseBoxScreenPayload::channels,
			ByteBufCodecs.VAR_INT, OpenBaseBoxScreenPayload::selectedChannelIndex,
			OpenBaseBoxScreenPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
