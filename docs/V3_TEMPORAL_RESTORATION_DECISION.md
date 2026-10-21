# V3 Temporal Restoration — Research Decision

Date: 2026-10-21

## Goal

Build a professional Android image/video enhancer that improves perceptual quality for real footage while keeping long-video processing practical. The design must avoid cosmetic-only scaling and avoid frame-by-frame heavy GAN inference for long videos.

## Validated external findings

1. **NanoVSR exists and is MIT licensed.** The published NanoVSR-644k model reports 28.64 dB on REDS4 and 27.2 FPS on Jetson Orin NX 16GB / 25W at 180x320 LR input with TensorRT FP16. This is useful evidence for the architecture family, but it is **not an Android phone benchmark**.
2. **NanoVSR is not a pure-Conv graph end-to-end.** After RepVGG reparameterization its recurrent body becomes plain convolutions, but the published architecture still contains PixelShuffle and PReLU in the upsampling blocks. Therefore LiteRT/NPU compatibility and full partitioning must be measured rather than assumed.
3. **CDA-VSR (CVPR 2026) validates compressed-domain priors as a serious direction.** It uses motion vectors, residual maps, and frame types to improve alignment/fusion/reconstruction efficiency.
4. **FFmpeg can expose motion vectors and video encoding parameters as frame side data for supported codecs.** This makes compressed-domain analysis technically possible, but software decode cost and battery cost on Android must be benchmarked before replacing MediaCodec.
5. **Media3 supports custom GlEffect / GlShaderProgram processing.** This is the preferred short-term path for custom GPU restoration while retaining proven decode/encode/mux plumbing.
6. **LiteRT 2.3 provides CompiledModel GPU/NPU paths, but accelerator support is device/model dependent.** The runtime therefore needs benchmark-driven fallback rather than a hard-coded assumption that NPU is always fastest.

## Claims explicitly NOT accepted without measurement

- “20,214 frames will finish in 2–4 minutes.”
- “The new architecture is 200–400x faster on Android.”
- “NanoVSR runs at 3 ms/frame on phone NPU.”
- “Software decode of 240p is effectively free on all target phones.”
- “Zero-copy is available across every GPU/NPU vendor path.”

These are hypotheses, not product guarantees.

## Architecture decision

### Images

- Realistic / Max image modes may use Real-ESRGAN or another heavy image model.
- Images are allowed to spend more compute because temporal throughput is not a requirement.
- Add a confidence/fidelity blend in later work to reduce hallucinated detail.

### Short video

- Prototype a true temporal VSR model (NanoVSR family first candidate).
- Start with the smallest practical model (226k) before 644k.
- Use a device microbenchmark to decide whether the temporal model is viable for this phone and resolution.
- If estimated runtime violates the speed budget, fall back to the professional GPU restoration path automatically.

### Long video

- Real-ESRGAN frame-by-frame is removed from Smart mode.
- Default path is a low-copy hardware decode → GPU restoration → hardware encode pipeline.
- True temporal AI is enabled only when a measured device/profile budget says it is practical.
- Future target is a custom recurrent student model trained specifically for real video degradation, ideally Y-focused with temporal state and confidence output.

## Near-term pipeline

```text
Media3 hardware decode
  -> custom GPU restoration stage
     - deblocking / ringing suppression
     - edge-aware reconstruction
     - temporal history on stable regions
     - color/tonal correction
  -> optional temporal-AI stage if device benchmark passes
  -> output-resolution GPU composite
  -> hardware H.264/HEVC encode
  -> mux original audio
```

No CPU Bitmap roundtrip is allowed in the default long-video path.

## Smart Planner V3

Planner inputs:

- source width/height
- duration and frame count
- requested output size
- measured decode throughput
- measured encoder throughput
- measured model latency for candidate accelerator(s)
- thermal state / headroom where available
- scene/content complexity (later)

Planner outputs:

- engine tier
- target resolution
- AI model tier
- accelerator
- estimated completion time
- fallback strategy

The planner must prefer a slightly lower neural tier over a many-times-longer export.

## NanoVSR feasibility gate

Before NanoVSR becomes a user-facing engine:

1. Export the MIT pretrained 226k and/or 644k checkpoints to ONNX.
2. Inspect graph operators and memory footprint.
3. Test at least one Android runtime path:
   - ONNX Runtime NNAPI/XNNPACK, or
   - LiteRT conversion if graph lowering is clean.
4. Measure real latency on Android hardware, including warmup and 10-minute sustained behavior.
5. Confirm temporal output quality on real compressed phone/video samples.
6. Reject the path if unsupported operations cause expensive CPU partitioning or if memory/output tensors are too large.

## Compressed-domain research gate

CDA-VSR-style use of MVs/QP/frame type is promising, but it is Phase 2 research. Do not ship a software-decoder replacement until tests show the total decode + analysis + AI pipeline beats MediaCodec in quality-per-second and energy.

## Acceptance gate for any future “approved APK”

An APK is not considered approved merely because CI builds it.

Required:

- unit tests pass
- APK build succeeds
- model/assets checks pass
- no Real-ESRGAN-per-frame path in Smart for long videos
- measurable progress and ETA are shown
- fallback never silently downgrades while claiming AI quality
- at least one real-device timing result is recorded
- output completes without mux/encoder failure
- visual comparison is performed on low-resolution compressed footage
- long-video mode has an explicit time budget and automatically avoids pathological AI runtimes

## Long-term R&D target

Train our own recurrent student model using heavy teachers only offline. The student should be optimized for real video degradation, temporal consistency, accelerator-friendly operators, confidence estimation, and low-resolution inference. This is the route most likely to deliver both professional quality and practical speed, but it requires a separate data/training program and cannot be honestly claimed as completed in the current APK.