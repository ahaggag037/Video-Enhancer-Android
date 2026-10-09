# Video Enhancer Android

Android video enhancement app built around AndroidX Media3 Transformer and GPU video effects.

## Current V1
- Pick a video from device storage.
- Hardware-accelerated decode/encode via Android MediaCodec.
- GPU color enhancement with brightness, contrast and saturation controls.
- High-quality Lanczos resampling presets up to 4K, subject to device codec limits.
- Saves the finished MP4 into `Movies/VideoEnhancer` on Android 10+.
- GitHub Actions builds an installable debug APK on every push.

> Note: V1 uses deterministic GPU enhancement/resampling. Neural super-resolution/denoise is a separate V2 engine because it requires a bundled on-device ML model and substantially higher compute/memory.
