# Video Enhancer V2 — Real AI Enhancement Design

## Objective

Build a usable Android APK that performs genuine learned video super-resolution, not cosmetic resizing, while preserving the already-verified V1 export path as a fast fallback. The default experience should prefer quality gains that are visibly meaningful, but avoid forcing an expensive neural path when it would waste time.

## Product contract

- **Real AI mode:** on-device Real-ESRGAN General x4v3 through Google LiteRT 2.3.0. The model performs learned x4 super-resolution on 128×128 RGB tiles.
- **Fast mode:** retain the existing Media3 GPU/Lanczos path, including the 120-second muxer-sample timeout that fixed the real-device export failure.
- **Smart mode:** inspect source resolution and requested output. Use AI for genuinely low-resolution sources where x4 recovery is valuable; use the fast path when the source is already near or above the requested output or the neural engine cannot initialize.
- **No false claims:** the UI must clearly distinguish neural AI enhancement from deterministic GPU enhancement.
- **Speed:** process frames in a streaming manner instead of collecting the entire video in memory. Reuse model buffers and tile buffers. Prefer GPU acceleration and fall back to CPU when LiteRT GPU is unavailable.
- **Compatibility:** Android minSdk remains 29. H.264 MP4 output remains the compatibility default. Preserve source audio by remuxing it without re-encoding when possible.

## AI model

Model: `realesr_general_x4v3.tflite` from `litert-community/real-esrgan-x4v3-litert`, BSD-3-Clause.

- Input: `[1, 128, 128, 3]`, NHWC RGB float32 in 0..1.
- Output: `[1, 3, 512, 512]`, NCHW RGB float32 in 0..1.
- Scale: x4.
- Build packaging: the model is downloaded in GitHub Actions and bundled into `app/src/main/assets`; it is not committed as a binary through the GitHub text-file connector.

## Architecture

### `EnhancementPlanner`
Pure Kotlin decision logic. Given source dimensions, target choice, and engine choice (`SMART`, `AI_DETAIL`, `FAST_GPU`), it returns a plan with target dimensions and selected path. It never silently labels deterministic scaling as AI.

### `RealEsrganUpscaler`
Owns LiteRT `CompiledModel`, input/output tensor buffers, tile preprocessing, inference and tile stitching. Initialization tries GPU first and falls back to CPU. All inference runs off the main thread.

Tiles are padded at image edges, inferred independently, stitched into a x4 canvas, and finally downscaled only when the requested output is smaller than the native x4 result. The first implementation prioritizes correctness and memory safety over overlap blending; seam reduction is a follow-up if real-device testing exposes tile boundaries.

### `AiVideoProcessor`
Streams video frames through Android media APIs. It reads metadata, selects frames at the source cadence, runs `RealEsrganUpscaler`, writes H.264 frames incrementally, and remuxes source audio. It reports stage, frame count, elapsed time, and percent progress. It never stores all enhanced frames simultaneously.

Because hardware codec behavior differs by device, the processor may fall back to the proven Media3 path with a visible explanation if AI decode/encode cannot start. A failure must terminate with an actionable error rather than spin indefinitely.

### `MainActivity`
Adds engine selection and determinate progress without moving ML/media implementation into the Activity. It delegates to the planner and processor and keeps the existing gallery-save behavior.

## Output sizing

- `Original`: preserve source dimensions.
- `1080p`, `1440p`, `2160p`: fit the source aspect ratio inside the chosen long-edge class without stretching.
- Dimensions are made even for H.264/YUV420 compatibility.
- Smart AI is selected when the source pixel count is materially below the requested output and the source long edge is <= 1280; otherwise the fast path is preferred.

## Error handling

- Model missing/corrupt: show `AI model unavailable` and fall back to Fast GPU in Smart mode; explicit AI mode reports the error.
- LiteRT GPU unavailable: retry CPU once.
- Codec/decode/encode failure: cancel resources, delete partial output, report the exact stage and reason.
- Export stall/muxer failure on fast path: keep the verified 120,000 ms muxer timeout and detailed error code.
- User leaves Activity: cancel active processor and release model/media resources.

## Testing

Unit tests cover planner decisions, target sizing, model tile-grid math, edge padding/stitch coordinates, progress calculation, and fallback policy. GitHub Actions runs unit tests before assembling the APK, downloads the pinned model, verifies its SHA-256, then builds and uploads the debug APK.

## NVIDIA path

NVIDIA Video Super Resolution NIM remains a future optional engine behind the same engine abstraction. Current official VSR delivery is a self-hosted gRPC NIM rather than a generally available free hosted endpoint, so this APK will not pretend an API-key-only path exists. The abstraction is intentionally compatible with adding a verified NVIDIA endpoint later without changing the UI contract.