# Binaural spatial audio

Settings → Spatial audio → **Binaural** renders stereo tracks for headphones: the track is upmixed to 5.1 and the
six channels are played through virtual speakers in a listening room, using measured head-related impulse
responses. It runs entirely inside BitChord's own float DSP chain (`playback/binaural/`), before the equaliser.

## Signal chain

```
stereo ──► upmixer (STFT, 2 → 5.1: L R C LFE Ls Rs) ──► 6 × 2 convolution (virtual speakers + room) ──► −8 dB ──► peak limiter ──► EQ ─► …
```

| Stage | File | What it does |
|---|---|---|
| Upmixer | `ScottyUpmixer.kt`, `ScottyTables.kt` | sqrt-Hann STFT (2048 / hop 1024 up to 48 kHz). Per-bin left/right covariance, smoothed over 0.27 octave and 130 ms; ambience is the smaller eigenvalue. Front and centre steering gains come from lookup tables; the ambience goes to the surrounds through three all-pass combs and an EQ. Everything below 80 Hz (Linkwitz-Riley) plus the centre feeds the LFE channel. The tables are generated at runtime from ~25 parameters. |
| Spatializer | `PartitionedConvolver.kt`, `BinauralIrs.kt` | Uniformly partitioned overlap-save convolution with the shipped 6 × 2 responses (`assets/binaural/v3_<rate>.bin`). Speakers sit at L/R ±49° / −10°, C 0° / −10°, Ls/Rs ±130°. The LFE is a dry 150 Hz low-passed feed. The direct sound sits 13 dB above a synthetic room (≈245 ms reverberation, no reverberant bass). |
| Output | `PeakLimiter.kt`, `BinauralRenderer.kt` | −8 dB headroom (the upmix adds peaks of up to ~9 dB, mostly bass), then a stereo-linked look-ahead limiter at −1 dBFS with a 250 ms release. It only has work to do on rare peaks, so the bass is not squashed. |

**Latency.** Output lags input by ≈ 2 hops + 5 ms, about 48 ms at 44.1/48 kHz. At end of stream the precision sink
pushes that much silence plus the room tail through the chain (`PrecisionAudioSink.drainTail`), so track endings
are not cut. Gapless tracks in the same format keep the renderer running across the boundary.

**Sample rates.** Responses ship for 44.1 and 48 kHz. Other rates from 22.05 to 192 kHz are resampled once, from
the matching family, and cached. Above 48 kHz the STFT frame doubles per octave, so its time and frequency
resolution stays the same.

**Verification.** `BinauralRendererTest` runs the renderer on a deterministic signal and compares it against the
reference implementation's output, `src/test/resources/binaural/golden_<rate>.bin`. The two must agree to better
than −80 dB.

## Where the design comes from

The processing structure is a reimplementation of the stereo → 5.1 → headphones chain behind macOS "Spatialize
Stereo". It was reverse-engineered and rebuilt independently; no Apple code or data is used or shipped. The HRTFs,
the room and the binaural responses are built from open data only:

* **HRTFs:** Meta Reality Labs Research, SS2 dataset, head-and-torso simulator measurement (`SS2_HATS051123_1`),
  licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Modified for this use: left/right
  mirror-symmetrised, diffuse-field equalised, low-frequency interaural delay kept and high frequencies
  time-aligned, truncated to 128 taps.
* **Room:** synthesised from the same HRTFs, using image sources plus stochastic reflections with a late tail.

The shipped `v3_<rate>.bin` files are these responses rendered through the spatializer. They are distributed under
CC BY 4.0, with the attribution above.
