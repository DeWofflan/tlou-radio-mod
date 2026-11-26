package com.tlouradio.compat;

import com.tlouradio.TlouRadioMod;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side tactical radio DSP pipeline executed once per speaker Opus frame (outside the receiver loop).
 * Applies a 48 kHz mono 300 Hz – 3000 Hz cascaded biquad bandpass filter, gentle soft-clipping,
 * and subtle tactical radio static, with safe fallback to raw Opus on any codec error.
 */
public final class RadioAudioDspProcessor {
	private static final double SAMPLE_RATE = 48_000.0D;
	private static final double HIGH_PASS_HZ = 300.0D;
	private static final double LOW_PASS_HZ = 3000.0D;
	private static final double FILTER_Q = 0.7071067811865476D; // Butterworth
	private static final double MAKEUP_GAIN = 1.28D;
	private static final double NOISE_FLOOR_IDLE = 110.0D;
	private static final double NOISE_MODULATION = 260.0D;

	private static final BiquadCoeffs HPF_COEFFS = BiquadCoeffs.highPass(SAMPLE_RATE, HIGH_PASS_HZ, FILTER_Q);
	private static final BiquadCoeffs LPF_COEFFS = BiquadCoeffs.lowPass(SAMPLE_RATE, LOW_PASS_HZ, FILTER_Q);

	private static final ConcurrentHashMap<UUID, SpeakerDspContext> CONTEXTS = new ConcurrentHashMap<>();

	private RadioAudioDspProcessor() {
	}

	/**
	 * Decodes {@code rawOpus}, applies tactical radio bandpass + static DSP, and re-encodes to Opus.
	 * Falls back to {@code rawOpus} if empty, null, or if any codec exception occurs.
	 */
	public static byte[] process(VoicechatApi api, UUID speakerId, byte[] rawOpus) {
		if (api == null || speakerId == null || rawOpus == null || rawOpus.length == 0) {
			return rawOpus;
		}
		SpeakerDspContext ctx = CONTEXTS.computeIfAbsent(speakerId, ignored -> new SpeakerDspContext(api));
		return ctx.processFrame(api, rawOpus);
	}

	public static void removeSpeaker(UUID speakerId) {
		if (speakerId == null) {
			return;
		}
		SpeakerDspContext removed = CONTEXTS.remove(speakerId);
		if (removed != null) {
			removed.close();
		}
	}

	public static void clearAll() {
		for (UUID key : CONTEXTS.keySet()) {
			SpeakerDspContext removed = CONTEXTS.remove(key);
			if (removed != null) {
				removed.close();
			}
		}
	}

	private record BiquadCoeffs(double b0, double b1, double b2, double a1, double a2) {
		private static BiquadCoeffs highPass(double sampleRate, double cutoffHz, double q) {
			double omega = 2.0D * Math.PI * cutoffHz / sampleRate;
			double cos = Math.cos(omega);
			double alpha = Math.sin(omega) / (2.0D * q);
			double a0 = 1.0D + alpha;
			return new BiquadCoeffs(
					((1.0D + cos) * 0.5D) / a0,
					(-(1.0D + cos)) / a0,
					((1.0D + cos) * 0.5D) / a0,
					(-2.0D * cos) / a0,
					(1.0D - alpha) / a0
			);
		}

		private static BiquadCoeffs lowPass(double sampleRate, double cutoffHz, double q) {
			double omega = 2.0D * Math.PI * cutoffHz / sampleRate;
			double cos = Math.cos(omega);
			double alpha = Math.sin(omega) / (2.0D * q);
			double a0 = 1.0D + alpha;
			return new BiquadCoeffs(
					((1.0D - cos) * 0.5D) / a0,
					(1.0D - cos) / a0,
					((1.0D - cos) * 0.5D) / a0,
					(-2.0D * cos) / a0,
					(1.0D - alpha) / a0
			);
		}
	}

	private static final class BiquadFilter {
		private final BiquadCoeffs c;
		private double z1;
		private double z2;

		private BiquadFilter(BiquadCoeffs coeffs) {
			this.c = coeffs;
		}

		private double process(double x) {
			// Direct Form II Transposed
			double y = this.c.b0 * x + this.z1;
			this.z1 = this.c.b1 * x - this.c.a1 * y + this.z2;
			this.z2 = this.c.b2 * x - this.c.a2 * y;
			return y;
		}
	}

	private static final class SpeakerDspContext {
		private OpusDecoder decoder;
		private OpusEncoder encoder;
		private final BiquadFilter hpf = new BiquadFilter(HPF_COEFFS);
		private final BiquadFilter lpf = new BiquadFilter(LPF_COEFFS);
		private final BiquadFilter noiseLpf = new BiquadFilter(LPF_COEFFS);
		private int rngState = 0x6D2B79F5;
		private boolean closed = false;

		private SpeakerDspContext(VoicechatApi api) {
			initCodecs(api);
		}

		private void initCodecs(VoicechatApi api) {
			try {
				this.decoder = api.createDecoder();
				this.encoder = api.createEncoder(OpusEncoderMode.VOIP);
			} catch (Throwable t) {
				TlouRadioMod.LOGGER.warn("TLOU2 Radio DSP codec initialization failed; falling back to raw audio.", t);
				closeCodecsQuietly();
			}
		}

		private synchronized byte[] processFrame(VoicechatApi api, byte[] rawOpus) {
			if (this.closed) {
				return rawOpus;
			}
			try {
				if (this.decoder == null || this.decoder.isClosed()
						|| this.encoder == null || this.encoder.isClosed()) {
					closeCodecsQuietly();
					initCodecs(api);
					if (this.decoder == null || this.encoder == null) {
						return rawOpus;
					}
				}

				short[] pcm = this.decoder.decode(rawOpus);
				if (pcm == null || pcm.length == 0) {
					return rawOpus;
				}

				for (int i = 0; i < pcm.length; i++) {
					double sample = pcm[i];
					double bandpassed = this.lpf.process(this.hpf.process(sample));

					// Gentle analog radio saturation
					double driven = (bandpassed * MAKEUP_GAIN) / 32768.0D;
					double saturated = Math.tanh(driven * 1.15D) * 30500.0D;

					// Subtle band-limited radio static/hiss
					double envelope = Math.min(1.0D, Math.abs(bandpassed) / 14000.0D);
					double white = nextWhiteNoise();
					double coloredNoise = this.noiseLpf.process(white) * (NOISE_FLOOR_IDLE + NOISE_MODULATION * envelope);

					double out = saturated + coloredNoise;
					if (out > Short.MAX_VALUE) {
						out = Short.MAX_VALUE;
					} else if (out < Short.MIN_VALUE) {
						out = Short.MIN_VALUE;
					}
					pcm[i] = (short) out;
				}

				byte[] encoded = this.encoder.encode(pcm);
				return (encoded != null && encoded.length > 0) ? encoded : rawOpus;
			} catch (Throwable t) {
				return rawOpus;
			}
		}

		private double nextWhiteNoise() {
			int x = this.rngState;
			x ^= (x << 13);
			x ^= (x >>> 17);
			x ^= (x << 5);
			this.rngState = x;
			return (x >> 16) / 32768.0D;
		}

		private synchronized void close() {
			this.closed = true;
			closeCodecsQuietly();
		}

		private void closeCodecsQuietly() {
			if (this.decoder != null) {
				try {
					if (!this.decoder.isClosed()) {
						this.decoder.close();
					}
				} catch (Throwable ignored) {
				}
				this.decoder = null;
			}
			if (this.encoder != null) {
				try {
					if (!this.encoder.isClosed()) {
						this.encoder.close();
					}
				} catch (Throwable ignored) {
				}
				this.encoder = null;
			}
		}
	}
}
