package com.tlouradio.client.renderer;

import com.geckolib.model.DefaultedBlockGeoModel;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.item.RadioBaseBoxBlockItem;

public class RadioBaseBoxItemGeoRenderer extends GeoItemRenderer<RadioBaseBoxBlockItem> {
	public RadioBaseBoxItemGeoRenderer() {
		super(new DefaultedBlockGeoModel<>(TlouRadioMod.id("radio_base_box")));
		useAlternateGuiLighting();
		withRenderLayer(AutoGlowingGeoLayer::new);
	}

	@Override
	public void adjustRenderPose(RenderPassInfo<GeoRenderState> renderPassInfo) {
		// Block model starts at Y=0 rather than Y=-8, so center cleanly inside the 1x1x1 item bounds
		renderPassInfo.poseStack().translate(0.5F, 0.14F, 0.5F);
	}
}
