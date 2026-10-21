# Video Enhancer V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an Android APK with a genuine on-device Real-ESRGAN video enhancement mode, an adaptive Smart mode, and the already-verified fast Media3 fallback.

**Architecture:** Separate pure planning/math, the LiteRT tile upscaler, the streaming AI video processor, and the Activity UI. The neural model is bundled at CI build time with a pinned SHA-256; the existing Media3 export path remains the reliable fallback and retains the muxer timeout fix.

**Tech Stack:** Kotlin, Android MediaCodec/MediaExtractor/MediaMuxer/MediaMetadataRetriever, Google LiteRT 2.3.0 + GPU accelerator, Media3 1.11.1, JUnit 4, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-21-video-enhancer-v2-design.md`

## Global Constraints

- minSdk stays 29.
- H.264 MP4 remains the default export format.
- Real-ESRGAN model is `realesr_general_x4v3.tflite`, x4, input 128×128 NHWC RGB float 0..1, output NCHW RGB float 0..1.
- Smart mode must never call deterministic scaling “AI”.
- AI processing must not keep the full enhanced video in memory.
- Fast fallback keeps `ExportPolicy.maxDelayBetweenMuxerSamplesMs = 120_000L`.
- The model must be downloaded from a pinned URL and SHA-256 checked before build.

## Review Focus

1. Portrait input and odd source dimensions must produce even output dimensions without aspect-ratio distortion.
2. GPU-incompatible devices must fall back to CPU or Fast GPU without infinite loading.
3. Edge tiles smaller than 128×128 must pad and crop correctly without writing outside the destination bitmap.
4. Videos with audio must retain an audio track when remuxing succeeds.
5. Any media/ML failure must terminate progress state and delete partial files.

---

### Task 1: Enhancement planning and target sizing

**Files:**
- Create: `app/src/main/java/com/haggag/videoenhancer/EnhancementPlanner.kt`
- Create: `app/src/test/java/com/haggag/videoenhancer/EnhancementPlannerTest.kt`

**Interfaces:**
- Produces: `EnhancementPlanner.EngineChoice`, `EnhancementPlanner.Path`, `EnhancementPlanner.Plan`, `EnhancementPlanner.plan(...)`, `EnhancementPlanner.targetSize(...)`.

- [ ] Write failing tests for Smart choosing AI on low-resolution upscale, Smart choosing Fast on already-large input, explicit AI staying AI, portrait sizing, and even dimensions.
- [ ] Run `gradle :app:testDebugUnitTest --tests '*EnhancementPlannerTest'` and verify RED because `EnhancementPlanner` does not exist.
- [ ] Implement the pure planner with no Android dependencies.
- [ ] Re-run the focused test and then the full unit-test suite; expect PASS.
- [ ] Commit the task.

### Task 2: Real-ESRGAN tile math and engine wrapper

**Files:**
- Create: `app/src/main/java/com/haggag/videoenhancer/AiTileGrid.kt`
- Create: `app/src/main/java/com/haggag/videoenhancer/RealEsrganUpscaler.kt`
- Create: `app/src/test/java/com/haggag/videoenhancer/AiTileGridTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: target sizing from Task 1.
- Produces: `AiTileGrid.tiles(width, height)`, `AiTileGrid.Tile`, and `RealEsrganUpscaler.upscale(bitmap, requestedWidth, requestedHeight): Bitmap`.

- [ ] Write failing tile-grid tests covering exact 128, non-multiple edges, x4 destination coordinates, and no out-of-bounds extents.
- [ ] Run focused test and verify RED because `AiTileGrid` is missing.
- [ ] Implement `AiTileGrid`.
- [ ] Add LiteRT 2.3.0 dependencies and implement `RealEsrganUpscaler` with GPU-first/CPU-fallback initialization, reusable buffers, NHWC input conversion and NCHW output conversion.
- [ ] Run tile tests and full unit tests; expect PASS; compile verifies LiteRT API use.
- [ ] Commit the task.

### Task 3: Streaming AI video processor

**Files:**
- Create: `app/src/main/java/com/haggag/videoenhancer/AiVideoProcessor.kt`
- Create: `app/src/main/java/com/haggag/videoenhancer/VideoMath.kt`
- Create: `app/src/test/java/com/haggag/videoenhancer/VideoMathTest.kt`

**Interfaces:**
- Consumes: `RealEsrganUpscaler` and `EnhancementPlanner.Plan`.
- Produces: `AiVideoProcessor.process(inputUri, plan, onProgress, onComplete, onError)`, `AiVideoProcessor.cancel()`, and `VideoMath.progressPercent(current,total)`.

- [ ] Write failing tests for progress clamping, zero-total safety, frame timestamp preservation math, and bitrate scaling.
- [ ] Verify RED.
- [ ] Implement `VideoMath`.
- [ ] Implement one-frame-at-a-time processing: metadata read, frame retrieval at source cadence, AI upscale, MediaCodec H.264 encode, MediaMuxer output, and source-audio remux when available. Release resources on every exit path.
- [ ] Run full unit tests and assemble once to catch Android media API compile errors.
- [ ] Commit the task.

### Task 4: V2 UI and fallback integration

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/haggag/videoenhancer/MainActivity.kt`

**Interfaces:**
- Consumes: planner + AI processor; retains existing `startEnhancement()` fast Media3 path.
- Produces: Smart / AI Detail / Fast GPU engine selection, determinate progress for AI, automatic Smart fallback, and truthful status text.

- [ ] Add engine spinner and progress percent UI.
- [ ] Wire Smart and AI Detail to `AiVideoProcessor`; Smart catches AI initialization/media failures and retries the proven Fast GPU path once.
- [ ] Preserve the verified muxer timeout and detailed `ExportPolicy` fast-path errors.
- [ ] Ensure cancellation on Activity finish and no double export.
- [ ] Run full unit tests and compile.
- [ ] Commit the task.

### Task 5: CI model pin, APK build, and delivery verification

**Files:**
- Modify: `.github/workflows/android-apk.yml`
- Modify: `app/build.gradle.kts` if needed for no-compress assets.
- Create: `THIRD_PARTY_MODELS.md`

**Interfaces:**
- Produces: reproducible debug APK artifact containing the pinned Real-ESRGAN model.

- [ ] Update workflow to run on `feature/v2-ai-enhancer`, download the model to `app/src/main/assets/realesr_general_x4v3.tflite`, and verify SHA-256 `6d597b46d812ada42bdde641ec6e78c0ef0b78ff5a825fcfd74b9a1b3bcbe9a4`.
- [ ] Document model source/license/provenance.
- [ ] Push a tests-first commit and observe expected RED before production implementation commits; then rerun CI after GREEN implementation.
- [ ] Verify `:app:testDebugUnitTest` PASS, `:app:assembleDebug` PASS, and artifact upload PASS.
- [ ] Download the artifact, extract `app-debug.apk`, run ZIP integrity checks, compute SHA-256, and deliver the APK.