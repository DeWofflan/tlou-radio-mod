package com.tlouradio.network.payload;

import com.tlouradio.TlouRadioMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * S2C synchronization of players currently transmitting via PTT so remote clients
 * can raise their 3rd-person arm in a spyglass-like pose.
 * <ul>
 *   <li>{@code fullSync == true}: replaces client's active transmitter set with {@code playerIds}.</li>
 *   <li>{@code fullSync == false}: updates each UUID in {@code playerIds} to {@code transmitting}.</li>
 * </ul>
 */
public record SyncRadioTransmitterPayload(
		boolean fullSync,
		boolean transmitting,
		List<UUID> playerIds
) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SyncRadioTransmitterPayload> TYPE =
			new CustomPacketPayload.Type<>(TlouRadioMod.id("sync_radio_transmitter"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SyncRadioTransmitterPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, SyncRadioTransmitterPayload::fullSync,
			ByteBufCodecs.BOOL, SyncRadioTransmitterPayload::transmitting,
			UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(256)), SyncRadioTransmitterPayload::playerIds,
			SyncRadioTransmitterPayload::new
	);

	public static SyncRadioTransmitterPayload singleUpdate(UUID playerId, boolean transmitting) {
		return new SyncRadioTransmitterPayload(false, transmitting, List.of(playerId));
	}

	public static SyncRadioTransmitterPayload fullSnapshot(List<UUID> activeTransmitters) {
		return new SyncRadioTransmitterPayload(true, true, List.copyOf(activeTransmitters));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
