package com.tlouradio.client.renderer;

import com.geckolib.model.DefaultedBlockGeoModel;
import com.geckolib.renderer.GeoItemRenderer;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.item.RadioBaseBoxBlockItem;

public class RadioBaseBoxItemGeoRenderer extends GeoItemRenderer<RadioBaseBoxBlockItem> {
	public RadioBaseBoxItemGeoRenderer() {
		super(new DefaultedBlockGeoModel<>(TlouRadioMod.id("radio_base_box")));
		useAlternateGuiLighting();
	}
}
