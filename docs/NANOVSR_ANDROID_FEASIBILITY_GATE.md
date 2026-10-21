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

## Verified gates — 2026-10-21

### Gate A1 — official ONNX anatomy probe: PASS

Official NanoVSR-226k v1.0 was exported in deploy form with a fixed probe shape `1x5x3x64x64` and passed ONNX checker.

Observed graph:

- 245 nodes total
- Conv: 101
- LeakyRelu: 81
- Add: 15
- DepthToSpace: 10
- PRelu: 10
- Gather: 10
- Concat: 6
- Resize: 5
- Unsqueeze: 5
- Reshape: 2

This confirms that accelerator compatibility must explicitly cover `DepthToSpace`, `PRelu`, `Resize`, recurrence-unrolled graph structure and concatenation.

### Gate A2 — direct PyTorch → LiteRT conversion: PASS

Following Google's current deployment direction, the product candidate was converted **directly with `litert-torch 0.9.4`**, not through ONNX.

Probe:

- PyTorch: 2.10.0 CPU build
- LiteRT Torch: 0.9.4
- detected model: 32 features / 8 blocks (NanoVSR-226k)
- fixed input: `1x5x3x64x64`
- fixed output: `1x5x3x256x256`
- `.tflite` size: 1,008,288 bytes
- max absolute error vs PyTorch: `1.3113021850585938e-06`
- mean absolute error: `1.1136772570807807e-07`
- parity PSNR on deterministic probe: ~136.44 dB
- serialized model reload max difference: `0.0`
- LiteRT artifact SHA-256: `db79706b1152f1b917b5c121a89d21a8123bee790e2ae2fa2245e2fed36d6675`

Result: conversion fidelity is strong enough to proceed to real-device runtime testing.

### Gate B1 — Android diagnostic APK build: PASS

A standalone package was built so it can be installed next to the product app without modifying the product path.

Diagnostic package intent:

- model: NanoVSR-226k
- fixed temporal window: 5
- fixed LR input: `180x320`
- output: `720x1280`
- runtime: LiteRT 2.3 `CompiledModel`
- accelerators tested on device: GPU then bounded CPU fallback
- metric: load/compile time, median model-only ms/window, effective model-only FPS
- CPU loop automatically stops early if a warm-up window is already extremely slow
- decode, GPU composition and video encoding are intentionally excluded from this first runtime gate

Build CI verified:

- official checkpoint hash
- direct LiteRT conversion for the realistic benchmark shape
- Android unit tests
- APK build
- NanoVSR asset present inside APK
- APK ZIP integrity

Model asset inside diagnostic APK SHA-256:
`d847b9470accda2f14328b01d8c1b94d65ca4b6b86e886b2f302ad60836dfd32`

Diagnostic APK SHA-256:
`3ce9c19fa637fb6d966b7ca940c2d9f0f98fadd8fa7d15b005c6a1034da1b0c3`

### Gate B2 — real Android execution: PENDING USER DEVICE RESULT

This is the current hard evidence boundary. The diagnostic APK must execute on a real Android phone and report GPU/CPU timings before NanoVSR can be admitted into Smart mode.

## Remaining admission gates

### Gate B — Accelerator execution / partitioning

For each target family (at minimum one Adreno and one Mali phone):

- record whether GPU model creation and inference succeed;
- record CPU fallback behavior;
- later add NPU testing where the platform/runtime supports it;
- reject any path whose unsupported operators force expensive host/device synchronization.

### Gate C — Sustained throughput

After the short functional benchmark passes, measure after warm-up and again after >= 10 minutes:

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

Real-ESRGAN remains available for image/max-quality experiments and very short clips. V3 Smart has moved from a fixed tile budget toward device-measured routing. NanoVSR is now proven convertible with excellent numerical parity, but **is not yet approved for product integration until real Android GPU/CPU execution is measured.**
