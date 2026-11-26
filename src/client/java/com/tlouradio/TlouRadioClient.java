package com.tlouradio;

import com.geckolib.animatable.client.GeoRenderProvider;
import com.geckolib.renderer.GeoItemRenderer;
import com.tlouradio.block.entity.ModBlockEntities;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.RadioClientState;
import com.tlouradio.client.gui.HandheldRadioScreen;
import com.tlouradio.client.gui.RadioBaseBoxScreen;
import com.tlouradio.client.gui.render.BaseBoxModelRenderer;
import com.tlouradio.client.gui.render.HandheldRadioModelRenderer;
import com.tlouradio.client.renderer.HandheldRadioGeoItemRenderer;
import com.tlouradio.client.renderer.RadioBaseBoxGeoRenderer;
import com.tlouradio.client.renderer.RadioBaseBoxItemGeoRenderer;
import com.tlouradio.network.payload.BaseBoxSaveResultPayload;
import com.tlouradio.network.payload.OpenBaseBoxScreenPayload;
import com.tlouradio.network.payload.RadioReceivingSignalPayload;
import com.tlouradio.network.payload.SyncRadioTransmitterPayload;
import com.tlouradio.radio.RadioClientHooks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;

public class TlouRadioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RadioClientHooks.registerRenderProviderFactories(
				() -> new GeoRenderProvider() {
					private GeoItemRenderer<?> renderer;

					@Override
					public GeoItemRenderer<?> getGeoItemRenderer() {
						if (this.renderer == null) {
							this.renderer = new HandheldRadioGeoItemRenderer();
						}
						return this.renderer;
					}
				},
				() -> new GeoRenderProvider() {
					private GeoItemRenderer<?> renderer;

					@Override
					public GeoItemRenderer<?> getGeoItemRenderer() {
						if (this.renderer == null) {
							this.renderer = new RadioBaseBoxItemGeoRenderer();
						}
						return this.renderer;
					}
				}
		);
		BlockEntityRendererRegistry.register(ModBlockEntities.RADIO_BASE_BOX, RadioBaseBoxGeoRenderer::new);

		HandheldRadioModelRenderer.initialize();
		BaseBoxModelRenderer.initialize();
		RadioClientInputHandler.initialize();
		RadioClientHooks.registerHandheldScreenOpener((hand, stack) ->
				Minecraft.getInstance().gui.setScreen(new HandheldRadioScreen(hand, stack))
		);
		registerClientNetworking();
		TlouRadioMod.LOGGER.info("TLOU2 Radio istemci katmani baslatildi.");
	}

	private static void registerClientNetworking() {
		ClientPlayNetworking.registerGlobalReceiver(OpenBaseBoxScreenPayload.TYPE, (payload, context) ->
				context.client().gui.setScreen(new RadioBaseBoxScreen(
						payload.pos(),
						payload.networkId(),
						payload.channels(),
						payload.selectedChannelIndex()
				))
		);

		ClientPlayNetworking.registerGlobalReceiver(SyncRadioTransmitterPayload.TYPE, (payload, context) -> {
			if (payload.fullSync()) {
				RadioClientState.replaceAllTransmitters(payload.playerIds());
			} else {
				for (var id : payload.playerIds()) {
					RadioClientState.setTransmitting(id, payload.transmitting());
				}
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(RadioReceivingSignalPayload.TYPE, (payload, context) ->
				RadioClientState.markReceivingSignal()
		);

		ClientPlayNetworking.registerGlobalReceiver(BaseBoxSaveResultPayload.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof RadioBaseBoxScreen screen) {
				screen.handleServerSaveResult(payload.success(), payload.message());
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RadioClientState.clear());
	}
}
