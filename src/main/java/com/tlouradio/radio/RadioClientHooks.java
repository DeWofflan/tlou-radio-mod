package com.tlouradio.radio;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Environment-safe client UI bridge so common item logic can open client screens
 * without referencing client-only classes under splitEnvironmentSourceSets.
 */
public final class RadioClientHooks {
	private static volatile BiConsumer<InteractionHand, ItemStack> handheldScreenOpener = (hand, stack) -> {
	};

	private RadioClientHooks() {
	}

	public static void registerHandheldScreenOpener(BiConsumer<InteractionHand, ItemStack> opener) {
		handheldScreenOpener = Objects.requireNonNull(opener, "opener");
	}

	public static void openHandheldRadioScreen(InteractionHand hand, ItemStack stack) {
		if (hand == null || stack == null || stack.isEmpty()) {
			return;
		}
		handheldScreenOpener.accept(hand, stack.copy());
	}
}
