package com.tlouradio.block.entity;

import com.geckolib.animatable.GeoBlockEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.radio.RadioBaseChannel;
import com.tlouradio.radio.RadioBaseNetworkSavedData;
import com.tlouradio.radio.RadioNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class RadioBaseBoxBlockEntity extends BlockEntity implements GeoBlockEntity {
	private static final String TAG_NETWORK_ID = "network_id";
	private static final String TAG_CHANNELS = "channels";
	private static final String TAG_SELECTED_CHANNEL = "selected_channel";
	// Legacy single-channel tags for backward compatibility
	private static final String TAG_LEGACY_FREQUENCY = "frequency";
	private static final String TAG_LEGACY_PASSCODE = "passcode";

	private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

	public record UpdateResult(
			boolean success,
			boolean channelsChanged,
			Optional<String> conflictFrequency,
			boolean duplicateInRequest,
			Set<String> newlyClaimedFrequencies
	) {
		public static UpdateResult conflict(String frequency) {
			return new UpdateResult(false, false, Optional.of(frequency), false, Set.of());
		}

		public static UpdateResult duplicate() {
			return new UpdateResult(false, false, Optional.empty(), true, Set.of());
		}

		public static UpdateResult ok(boolean channelsChanged, Set<String> newlyClaimedFrequencies) {
			return new UpdateResult(true, channelsChanged, Optional.empty(), false, newlyClaimedFrequencies);
		}
	}

	private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

	private UUID networkId = UUID.randomUUID();
	private final List<RadioBaseChannel> channels = new ArrayList<>(List.of(RadioBaseChannel.DEFAULT));
	private int selectedChannelIndex = 0;

	public RadioBaseBoxBlockEntity(BlockPos pos, BlockState blockState) {
		super(ModBlockEntities.RADIO_BASE_BOX, pos, blockState);
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>("base_box_controller", 4, state -> state.setAndContinue(IDLE_ANIM)));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return this.geoCache;
	}

	public UUID getNetworkId() {
		return this.networkId;
	}

	public List<RadioBaseChannel> getChannels() {
		if (this.channels.isEmpty()) {
			this.channels.add(RadioBaseChannel.DEFAULT);
		}
		return List.copyOf(this.channels);
	}

	public int getSelectedChannelIndex() {
		if (this.channels.isEmpty()) {
			this.channels.add(RadioBaseChannel.DEFAULT);
			this.selectedChannelIndex = 0;
			return 0;
		}
		this.selectedChannelIndex = Math.clamp(this.selectedChannelIndex, 0, this.channels.size() - 1);
		return this.selectedChannelIndex;
	}

	public RadioBaseChannel getSelectedChannel() {
		return this.channels.get(getSelectedChannelIndex());
	}

	public String getFrequency() {
		return getSelectedChannel().frequency();
	}

	public String getPasscode() {
		return getSelectedChannel().passcode();
	}

	/**
	 * Ensures this Base Box has a unique networkId (regenerating on Creative NBT copy collisions),
	 * resolves any default/copied frequency conflicts by assigning the first available frequency,
	 * persists to Overworld {@link RadioBaseNetworkSavedData}, and revalidates free listeners on newly claimed frequencies.
	 */
	public void ensureRegistered(ServerLevel serverLevel) {
		GlobalPos currentPos = GlobalPos.of(serverLevel.dimension(), this.worldPosition.immutable());
		RadioBaseNetworkSavedData savedData = RadioBaseNetworkSavedData.get(serverLevel.getServer());

		boolean dirty = false;
		while (savedData.isRegisteredAtDifferentPos(this.networkId, currentPos)) {
			this.networkId = UUID.randomUUID();
			dirty = true;
		}

		if (this.channels.isEmpty()) {
			this.channels.add(RadioBaseChannel.DEFAULT);
			this.selectedChannelIndex = 0;
			dirty = true;
		}

		boolean wasRegistered = savedData.getEntry(this.networkId).isPresent();
		Set<String> usedInThisBox = new HashSet<>();
		List<RadioBaseChannel> resolved = new ArrayList<>(this.channels.size());
		Set<String> claimedNow = new HashSet<>();

		for (RadioBaseChannel ch : this.channels) {
			String normFreq = RadioStateComponent.normalizeFrequency(ch.frequency());
			Optional<RadioBaseNetworkSavedData.FrequencyOwner> ownerOpt = savedData.lookupFrequency(normFreq);
			boolean conflictsWithOther = ownerOpt.isPresent() && !ownerOpt.get().networkId().equals(this.networkId);
			if (conflictsWithOther || usedInThisBox.contains(normFreq)) {
				String freeFreq = savedData.findFirstAvailableFrequency(normFreq, this.networkId, usedInThisBox);
				resolved.add(new RadioBaseChannel(freeFreq, ch.passcode()));
				usedInThisBox.add(freeFreq);
				claimedNow.add(freeFreq);
				dirty = true;
			} else {
				resolved.add(new RadioBaseChannel(normFreq, ch.passcode()));
				usedInThisBox.add(normFreq);
				if (!wasRegistered) {
					claimedNow.add(normFreq);
				}
			}
		}

		if (dirty) {
			this.channels.clear();
			this.channels.addAll(resolved);
			this.selectedChannelIndex = Math.clamp(this.selectedChannelIndex, 0, this.channels.size() - 1);
			setChanged();
		}

		savedData.registerOrUpdate(this.networkId, this.channels, currentPos);

		if (!claimedNow.isEmpty()) {
			RadioNetworkManager.revalidateFreeListenersForFrequencies(serverLevel.getServer(), claimedNow);
		}
	}

	/**
	 * Validates and persists the multi-channel configuration for this Base Box.
	 * Rejects duplicate frequencies within the box or frequencies already claimed by another Base Box.
	 */
	public UpdateResult updateConfiguration(
			ServerLevel serverLevel,
			List<RadioBaseChannel> requestedChannels,
			int requestedSelectedIndex
	) {
		ensureRegistered(serverLevel);

		if (requestedChannels == null || requestedChannels.isEmpty()
				|| requestedChannels.size() > RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX) {
			return UpdateResult.duplicate();
		}

		List<RadioBaseChannel> cleaned = new ArrayList<>(requestedChannels.size());
		Set<String> seenInRequest = new LinkedHashSet<>();
		for (RadioBaseChannel raw : requestedChannels) {
			if (raw == null) {
				return UpdateResult.duplicate();
			}
			String normFreq = RadioStateComponent.normalizeFrequency(raw.frequency());
			if (!RadioStateComponent.isValidFrequency(normFreq) || !RadioStateComponent.isValidPasscode(raw.passcode())) {
				return UpdateResult.duplicate();
			}
			if (!seenInRequest.add(normFreq)) {
				return UpdateResult.duplicate();
			}
			cleaned.add(new RadioBaseChannel(normFreq, raw.passcode()));
		}

		RadioBaseNetworkSavedData savedData = RadioBaseNetworkSavedData.get(serverLevel.getServer());
		Optional<String> conflict = savedData.findConflictingFrequency(this.networkId, cleaned);
		if (conflict.isPresent()) {
			return UpdateResult.conflict(conflict.get());
		}

		Set<String> previousFreqs = new HashSet<>();
		for (RadioBaseChannel oldCh : this.channels) {
			previousFreqs.add(RadioStateComponent.normalizeFrequency(oldCh.frequency()));
		}

		Set<String> newlyClaimed = new HashSet<>();
		for (RadioBaseChannel newCh : cleaned) {
			if (!previousFreqs.contains(newCh.frequency())) {
				newlyClaimed.add(newCh.frequency());
			}
		}

		int clampedIndex = Math.clamp(requestedSelectedIndex, 0, cleaned.size() - 1);
		boolean channelsChanged = !this.channels.equals(cleaned);
		boolean selectionChanged = this.selectedChannelIndex != clampedIndex;

		this.channels.clear();
		this.channels.addAll(cleaned);
		this.selectedChannelIndex = clampedIndex;

		GlobalPos currentPos = GlobalPos.of(serverLevel.dimension(), this.worldPosition.immutable());
		savedData.registerOrUpdate(this.networkId, this.channels, currentPos);

		if (channelsChanged || selectionChanged) {
			setChanged();
		}
		return UpdateResult.ok(channelsChanged, newlyClaimed);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store(TAG_NETWORK_ID, UUIDUtil.CODEC, this.networkId);
		output.store(TAG_CHANNELS, RadioBaseChannel.CODEC.listOf(), getChannels());
		output.putInt(TAG_SELECTED_CHANNEL, getSelectedChannelIndex());
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.networkId = input.read(TAG_NETWORK_ID, UUIDUtil.CODEC).orElseGet(UUID::randomUUID);

		List<RadioBaseChannel> loadedChannels = input.read(TAG_CHANNELS, RadioBaseChannel.CODEC.listOf()).orElse(List.of());
		this.channels.clear();
		if (!loadedChannels.isEmpty()) {
			this.channels.addAll(RadioBaseNetworkSavedData.sanitizeChannels(loadedChannels));
		} else {
			// Backward-compatible load for legacy single-channel Base Boxes
			String legacyFreq = input.getStringOr(TAG_LEGACY_FREQUENCY, RadioStateComponent.DEFAULT_FREQUENCY).trim();
			String legacyPass = input.getStringOr(TAG_LEGACY_PASSCODE, "");
			this.channels.add(new RadioBaseChannel(legacyFreq, legacyPass));
		}
		if (this.channels.isEmpty()) {
			this.channels.add(RadioBaseChannel.DEFAULT);
		}
		int loadedIndex = input.getIntOr(TAG_SELECTED_CHANNEL, 0);
		this.selectedChannelIndex = Math.clamp(loadedIndex, 0, this.channels.size() - 1);

		if (this.level instanceof ServerLevel serverLevel) {
			ensureRegistered(serverLevel);
		}
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
		super.preRemoveSideEffects(pos, oldState);
		if (this.level instanceof ServerLevel serverLevel) {
			BlockState newState = serverLevel.getBlockState(pos);
			if (!newState.is(oldState.getBlock())) {
				RadioBaseNetworkSavedData savedData = RadioBaseNetworkSavedData.get(serverLevel.getServer());
				if (savedData.unregister(this.networkId)) {
					RadioNetworkManager.invalidateBaseNetwork(serverLevel.getServer(), this.networkId, true);
				}
			}
		}
	}
}
