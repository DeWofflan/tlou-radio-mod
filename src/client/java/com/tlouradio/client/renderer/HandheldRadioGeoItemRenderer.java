package com.tlouradio.client.renderer;

import com.geckolib.constant.DataTickets;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedItemGeoModel;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import com.google.common.reflect.TypeToken;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

public class HandheldRadioGeoItemRenderer extends GeoItemRenderer<HandheldRadioItem> {
	public static final DataTicket<Boolean> POWERED_TICKET =
			DataTicket.create("tlouradio_item_powered", new TypeToken<>() {});
	private static final DataTicket<Boolean> ANTENNA_EXTENDED_TICKET =
			DataTicket.create("tlouradio_item_antenna_extended", new TypeToken<>() {});
	private static final DataTicket<Float> DIAL_ANGLE_RAD_TICKET =
			DataTicket.create("tlouradio_item_dial_angle_rad", new TypeToken<>() {});
	private static final DataTicket<Float> FLICK_PROGRESS_TICKET =
			DataTicket.create("tlouradio_item_flick_progress", new TypeToken<>() {});
	private static final DataTicket<Float> PTT_PROGRESS_TICKET =
			DataTicket.create("tlouradio_item_ptt_progress", new TypeToken<>() {});

	public HandheldRadioGeoItemRenderer() {
		super(new DefaultedItemGeoModel<>(TlouRadioMod.id("handheld_radio")));
		useAlternateGuiLighting();
		withRenderLayer(new PoweredLcdGlowLayer(this));
	}

	@Override
	public void captureDefaultRenderState(
			HandheldRadioItem animatable,
			RenderData renderData,
			GeoRenderState renderState,
			float partialTick
	) {
		super.captureDefaultRenderState(animatable, renderData, renderState, partialTick);

		ItemStack stack = renderData.itemStack();
		RadioStateComponent radioState = stack.getOrDefault(
				ModDataComponents.RADIO_STATE,
				RadioStateComponent.DEFAULT
		);

		ItemDisplayContext perspective = renderData.renderPerspective();
		boolean isFirstPerson = perspective == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
				|| perspective == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;

		float flick = 0.0F;
		float ptt = 0.0F;
		if (isFirstPerson) {
			RadioClientInputHandler.updateRenderAnimationFrame();
			InteractionHand hand = (perspective == ItemDisplayContext.FIRST_PERSON_LEFT_HAND)
					? InteractionHand.OFF_HAND
					: InteractionHand.MAIN_HAND;
			flick = RadioClientInputHandler.getFlickProgress(hand);
			ptt = RadioClientInputHandler.getPttRaiseProgress();
		}

		renderState.addGeckolibData(POWERED_TICKET, radioState.isPowered());
		renderState.addGeckolibData(ANTENNA_EXTENDED_TICKET, radioState.antennaExtended());
		renderState.addGeckolibData(DIAL_ANGLE_RAD_TICKET, computeDialAngleRad(radioState.frequency()));
		renderState.addGeckolibData(FLICK_PROGRESS_TICKET, flick);
		renderState.addGeckolibData(PTT_PROGRESS_TICKET, ptt);
	}

	@Override
	public void adjustModelBonesForRender(RenderPassInfo<GeoRenderState> renderPassInfo, BoneSnapshots snapshots) {
		GeoRenderState state = renderPassInfo.renderState();
		boolean powered = state.getOrDefaultGeckolibData(POWERED_TICKET, false);
		boolean antennaExtended = state.getOrDefaultGeckolibData(ANTENNA_EXTENDED_TICKET, true);
		float dialAngleRad = state.getOrDefaultGeckolibData(DIAL_ANGLE_RAD_TICKET, 0.0F);
		float flick = state.getOrDefaultGeckolibData(FLICK_PROGRESS_TICKET, 0.0F);
		float ptt = state.getOrDefaultGeckolibData(PTT_PROGRESS_TICKET, 0.0F);

		snapshots.ifPresent("led_on", bone -> bone.skipRender(!powered));
		snapshots.ifPresent("led_off", bone -> bone.skipRender(powered));
		snapshots.ifPresent("dial_knob", bone -> bone.setRotY(dialAngleRad));

		snapshots.ifPresent("ptt_button", bone -> {
			if (ptt > 0.01F) {
				bone.setTranslateX(0.55F * ptt);
			}
		});

		float extRatio;
		if (!antennaExtended) {
			extRatio = 0.0F;
		} else if (flick > 0.001F) {
			float snapT = Mth.clamp(flick / 0.48F, 0.0F, 1.0F);
			float easeOut = 1.0F - (1.0F - snapT) * (1.0F - snapT);
			float overshoot = Mth.sin(flick * Mth.PI * 3.0F) * (1.0F - flick) * 0.10F;
			extRatio = Mth.clamp(easeOut + overshoot, 0.0F, 1.06F);
		} else {
			extRatio = 1.0F;
		}

		float clampedExt = Mth.clamp(extRatio, 0.0F, 1.0F);
		snapshots.ifPresent("antenna_seg1", seg1 -> seg1.setTranslateY(-1.80F * (1.0F - clampedExt)));
		snapshots.ifPresent("antenna_seg2", seg2 -> seg2.setTranslateY(-2.20F * (1.0F - clampedExt)));

		snapshots.ifPresent("antenna", bone -> {
			if (antennaExtended && flick > 0.001F) {
				float whipAngle = Mth.sin(flick * Mth.PI * 3.5F) * (1.0F - flick) * 11.0F * Mth.DEG_TO_RAD;
				bone.setRotX(bone.getRotX() - whipAngle);
				bone.setRotZ(bone.getRotZ() + whipAngle * 0.65F);
			} else if (antennaExtended && ptt > 0.01F) {
				double ageTicks = state.getOrDefaultGeckolibData(DataTickets.ANIMATABLE_INSTANCE_ID, 0L)
						+ (System.currentTimeMillis() / 50.0D);
				float sway = Mth.sin((float) (ageTicks * 0.45D)) * 2.2F * Mth.DEG_TO_RAD * ptt;
				bone.setRotX(bone.getRotX() + sway);
				bone.setRotZ(bone.getRotZ() - sway * 0.8F);
			}
		});
	}

	public static float computeDialAngleRad(String frequency) {
		if (frequency == null || frequency.isEmpty()) {
			return 0.0F;
		}
		try {
			double parsed = Double.parseDouble(frequency);
			int tenths = (int) Math.round(parsed * 10.0D);
			return ((tenths - 1000) * 6.0F) * Mth.DEG_TO_RAD;
		} catch (NumberFormatException ignored) {
			return 0.0F;
		}
	}

	/**
	 * Emissive layer active strictly when {@code isPowered == true}. Illuminates both the greenish
	 * LCD screen and the top status LED in 1st-person, 3rd-person, GUI, and dropped ground ItemEntities.
	 */
	private static final class PoweredLcdGlowLayer
			extends AutoGlowingGeoLayer<HandheldRadioItem, GeoItemRenderer.RenderData, GeoRenderState> {
		private PoweredLcdGlowLayer(HandheldRadioGeoItemRenderer renderer) {
			super(renderer);
		}

		@Override
		protected boolean shouldAddZOffset(GeoRenderState renderState) {
			return true;
		}

		@Override
		protected @Nullable RenderType getRenderType(GeoRenderState renderState) {
			if (!renderState.getOrDefaultGeckolibData(POWERED_TICKET, false)) {
				return null;
			}
			return super.getRenderType(renderState);
		}
	}
}
