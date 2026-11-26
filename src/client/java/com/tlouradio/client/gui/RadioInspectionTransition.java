package com.tlouradio.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;

/**
 * Handles smooth ~220ms ease-out opening and seamless, jump-free ~210ms ease-in-out closing
 * transitions for 3D tactical radio inspection screens, coordinating 3D model hand-lift
 * and safe camera FOV zoom.
 */
public final class RadioInspectionTransition {
	private static final float OPEN_DURATION_SECONDS = 0.22F;
	private static final float CLOSE_DURATION_SECONDS = 0.21F;
	private static final float TARGET_FOV_MULTIPLIER = 0.86F;

	private static volatile float activeGlobalProgress = 0.0F;

	private float openElapsedRatio = 0.0F;
	private float closeElapsedRatio = 0.0F;
	private float closeStartVisualProgress = 1.0F;
	private float currentVisualProgress = 0.0F;
	private boolean closing = false;
	private boolean closeCompleted = false;
	private long lastUpdateNanos = System.nanoTime();

	public RadioInspectionTransition() {
		activeGlobalProgress = 0.0F;
	}

	public void updateFrame() {
		long now = System.nanoTime();
		float dt = Mth.clamp((now - this.lastUpdateNanos) / 1_000_000_000.0F, 0.0F, 0.08F);
		this.lastUpdateNanos = now;

		if (this.closing) {
			this.closeElapsedRatio = Math.min(1.0F, this.closeElapsedRatio + (dt / CLOSE_DURATION_SECONDS));
			float u = this.closeElapsedRatio;
			// Smoothstep 0->1 reversed cleanly from closeStartVisualProgress down to 0 without any discontinuity
			float smoothDown = 1.0F - (u * u * (3.0F - 2.0F * u));
			this.currentVisualProgress = this.closeStartVisualProgress * smoothDown;
			if (this.closeElapsedRatio >= 1.0F) {
				this.currentVisualProgress = 0.0F;
				this.closeCompleted = true;
			}
		} else {
			this.openElapsedRatio = Math.min(1.0F, this.openElapsedRatio + (dt / OPEN_DURATION_SECONDS));
			float inv = 1.0F - this.openElapsedRatio;
			this.currentVisualProgress = 1.0F - (inv * inv * inv);
		}

		activeGlobalProgress = this.currentVisualProgress;
	}

	public void startClosing() {
		if (!this.closing) {
			this.closeStartVisualProgress = Math.max(0.01F, this.currentVisualProgress);
			this.closeElapsedRatio = 0.0F;
			this.closing = true;
			this.lastUpdateNanos = System.nanoTime();
		}
	}

	public boolean isClosing() {
		return this.closing;
	}

	public boolean isCloseCompleted() {
		return this.closeCompleted;
	}

	public float easedProgress() {
		return Mth.clamp(this.currentVisualProgress, 0.0F, 1.0F);
	}

	public static void resetGlobalFovImmediately() {
		activeGlobalProgress = 0.0F;
	}

	public static float computeSafeFovMultiplier() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || !mc.player.isAlive() || mc.level == null) {
			activeGlobalProgress = 0.0F;
			return 1.0F;
		}
		Screen currentScreen = mc.gui.screen();
		if (!(currentScreen instanceof HandheldRadioScreen) && !(currentScreen instanceof RadioBaseBoxScreen)) {
			activeGlobalProgress = 0.0F;
			return 1.0F;
		}
		return Mth.lerp(Mth.clamp(activeGlobalProgress, 0.0F, 1.0F), 1.0F, TARGET_FOV_MULTIPLIER);
	}
}
