package com.tlouradio.client.renderer;

import com.geckolib.model.DefaultedBlockGeoModel;
import com.geckolib.renderer.GeoBlockRenderer;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.block.entity.RadioBaseBoxBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

public class RadioBaseBoxGeoRenderer extends GeoBlockRenderer<RadioBaseBoxBlockEntity, BlockEntityRenderState> {
	public RadioBaseBoxGeoRenderer(BlockEntityRendererProvider.Context context) {
		super(context, new DefaultedBlockGeoModel<>(TlouRadioMod.id("radio_base_box")));
	}
}
