package com.tlouradio.item;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.block.ModBlocks;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Function;

public final class ModItems {
	public static final Item HANDHELD_RADIO = registerItem(
			"handheld_radio",
			HandheldRadioItem::new,
			new Item.Properties()
					.stacksTo(1)
					.component(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT)
	);

	public static final Item PCB_BOARD = registerItem(
			"pcb_board",
			Item::new,
			new Item.Properties()
	);

	public static final Item RADIO_BATTERY = registerItem(
			"radio_battery",
			Item::new,
			new Item.Properties()
	);

	public static final Item PLASTIC_SHEET = registerItem(
			"plastic_sheet",
			Item::new,
			new Item.Properties()
	);

	public static final CreativeModeTab CREATIVE_TAB = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			TlouRadioMod.id("general"),
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.tlouradio"))
					.icon(() -> new ItemStack(HANDHELD_RADIO))
					.displayItems((parameters, output) -> {
						output.accept(HANDHELD_RADIO);
						output.accept(ModBlocks.RADIO_BASE_BOX);
						output.accept(PCB_BOARD);
						output.accept(RADIO_BATTERY);
						output.accept(PLASTIC_SHEET);
					})
					.build()
	);

	private ModItems() {
	}

	private static Item registerItem(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, TlouRadioMod.id(name));
		Item item = factory.apply(properties.setId(itemKey));
		return Registry.register(BuiltInRegistries.ITEM, itemKey, item);
	}

	public static void initialize() {
	}
}
