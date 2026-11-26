package com.tlouradio.item;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import com.tlouradio.radio.RadioClientHooks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

public class RadioBaseBoxBlockItem extends BlockItem implements GeoItem {
	private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
	private volatile Object cachedRenderProvider;

	public RadioBaseBoxBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public Object getRenderProvider() {
		Object provider = this.cachedRenderProvider;
		if (provider == null) {
			provider = RadioClientHooks.createBaseBoxRenderProvider();
			this.cachedRenderProvider = provider;
		}
		return provider != null ? provider : GeoItem.super.getRenderProvider();
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.geoCache;
	}
}
