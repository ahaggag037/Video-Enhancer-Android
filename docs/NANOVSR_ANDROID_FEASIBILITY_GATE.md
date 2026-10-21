# NanoVSR Android Feasibility Gate

Status: **R&D candidate only — not admitted to the product path yet.**

## Why it is interesting

NanoVSR is an MIT-licensed ECCV 2026 video super-resolution architecture aimed at edge devices. The upstream 226k model is ~1.1 MB as a PyTorch checkpoint and reports 43.86 FPS for 180x320 input on Jetson Orin NX 16GB / 25W. Those numbers are useful evidence that the architecture is compact, but they are **not Android phone measurements** and must not be used as product ETA.

## Verified upstream graph characteristics

The released implementation contains:

- Conv2d
- BatchNorm during training, fused into deploy Conv2d
- LeakyReLU
- forward/backward temporal recurrence
- tensor concatenation + 1x1 fusion convolution
- PixelShuffle x2 twice
- PReLU in the upsampling blocks
- bilinear interpolation for the residual base
- additive residual output

Therefore the deploy graph is *not* simply a stack of 3x3 convolutions. PixelShuffle/PReLU and the fixed temporal window must be validated on LiteRT GPU/NPU instead of assuming full accelerator coverage.

## Admission gate

NanoVSR enters the Android app only if all gates pass on real phones.

### Gate A — Conversion

- Export the upstream 226k model in deploy form.
- Convert to the selected Android runtime without numerical failure.
- Compare converted output against PyTorch on a fixed clip.
- Reject if conversion requires custom CPU-only operators in the hot path.

### Gate B — Accelerator partitioning

For each target family (at minimum one Adreno and one Mali phone):

- record accelerator backend actually selected;
- record any CPU fallback / graph partitioning;
- reject the backend if unsupported ops cause repeated device/host synchronization.

### Gate C — Sustained throughput

Measure after warm-up and again after >= 10 minutes:

- ms/frame;
- effective FPS;
- thermal status/headroom;
- memory peak;
- energy where available.

No Jetson/Desktop number is accepted as an Android substitute.

### Gate D — Temporal quality

Compare against current Fast GPU and frame-wise Real-ESRGAN on complete clips, not isolated still frames:

- temporal flicker / crawling;
- edge stability;
- face identity stability;
- text stability;
- user preference on full sequences.

### Gate E — Product time budget

Smart mode may select a temporal neural path only when a short on-device benchmark predicts a practical completion time. Estimates must be based on this device, this runtime, and this model version.

## First candidate

Start with **NanoVSR-226k**, not 644k, because the project objective is quality-per-millisecond. Only move upward in model size if measured quality gain justifies the sustained thermal cost.

## Current decision

Real-ESRGAN remains available for image/max-quality experiments and very short clips. V3 Smart is moving from a fixed tile budget to device-measured routing first. NanoVSR integration comes only after the above conversion/accelerator gates are proven.
