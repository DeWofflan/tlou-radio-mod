package com.tlouradio.block.entity;

import com.tlouradio.component.RadioStateComponent;
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

import java.util.Objects;
import java.util.UUID;

public class RadioBaseBoxBlockEntity extends BlockEntity {
	private static final String TAG_NETWORK_ID = "network_id";
	private static final String TAG_FREQUENCY = "frequency";
	private static final String TAG_PASSCODE = "passcode";

	private UUID networkId = UUID.randomUUID();
	private String frequency = RadioStateComponent.DEFAULT_FREQUENCY;
	private String passcode = "";

	public RadioBaseBoxBlockEntity(BlockPos pos, BlockState blockState) {
		super(ModBlockEntities.RADIO_BASE_BOX, pos, blockState);
	}

	public UUID getNetworkId() {
		return this.networkId;
	}

	public String getFrequency() {
		return this.frequency;
	}

	public String getPasscode() {
		return this.passcode;
	}

	/**
	 * Ensures this Base Box has a unique networkId (regenerating on Creative NBT copy collisions)
	 * and is persisted in Overworld {@link RadioBaseNetworkSavedData}.
	 */
	public void ensureRegistered(ServerLevel serverLevel) {
		GlobalPos currentPos = GlobalPos.of(serverLevel.dimension(), this.worldPosition.immutable());
		RadioBaseNetworkSavedData savedData = RadioBaseNetworkSavedData.get(serverLevel.getServer());

		boolean reassigned = false;
		while (savedData.isRegisteredAtDifferentPos(this.networkId, currentPos)) {
			this.networkId = UUID.randomUUID();
			reassigned = true;
		}
		if (reassigned) {
			setChanged();
		}

		savedData.registerOrUpdate(this.networkId, this.frequency, this.passcode, currentPos);
	}

	/**
	 * Updates frequency and passcode in both the BlockEntity and Overworld SavedData.
	 *
	 * @return true if either frequency or passcode actually changed.
	 */
	public boolean updateConfiguration(ServerLevel serverLevel, String newFrequency, String newPasscode) {
		ensureRegistered(serverLevel);

		String cleanFreq = Objects.requireNonNullElse(newFrequency, RadioStateComponent.DEFAULT_FREQUENCY).trim();
		String cleanPass = Objects.requireNonNullElse(newPasscode, "");

		boolean changed = !this.frequency.equals(cleanFreq) || !this.passcode.equals(cleanPass);
		this.frequency = cleanFreq;
		this.passcode = cleanPass;

		GlobalPos currentPos = GlobalPos.of(serverLevel.dimension(), this.worldPosition.immutable());
		RadioBaseNetworkSavedData.get(serverLevel.getServer())
				.registerOrUpdate(this.networkId, this.frequency, this.passcode, currentPos);

		if (changed) {
			setChanged();
		}
		return changed;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store(TAG_NETWORK_ID, UUIDUtil.CODEC, this.networkId);
		output.putString(TAG_FREQUENCY, this.frequency);
		output.putString(TAG_PASSCODE, this.passcode);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.networkId = input.read(TAG_NETWORK_ID, UUIDUtil.CODEC).orElseGet(UUID::randomUUID);
		String loadedFrequency = input.getStringOr(TAG_FREQUENCY, RadioStateComponent.DEFAULT_FREQUENCY).trim();
		this.frequency = RadioStateComponent.isValidFrequency(loadedFrequency)
				? loadedFrequency
				: RadioStateComponent.DEFAULT_FREQUENCY;
		String loadedPasscode = input.getStringOr(TAG_PASSCODE, "");
		this.passcode = RadioStateComponent.isValidPasscode(loadedPasscode) ? loadedPasscode : "";

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
