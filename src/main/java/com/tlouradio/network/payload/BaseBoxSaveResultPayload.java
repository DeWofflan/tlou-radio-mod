package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C result for {@link UpdateBaseBoxConfigPayload} so {@code RadioBaseBoxScreen} can close smoothly
 * on success or stay open and display the localized conflict/error banner inline.
 */
public record BaseBoxSaveResultPayload(
		boolean success,
		Component message
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BaseBoxSaveResultPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("base_box_save_result"));

	public static final StreamCodec<RegistryFriendlyByteBuf, BaseBoxSaveResultPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, BaseBoxSaveResultPayload::success,
			ComponentSerialization.TRUSTED_STREAM_CODEC, BaseBoxSaveResultPayload::message,
			BaseBoxSaveResultPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
