# Third-party AI models and reference code

## Real-ESRGAN General x4v3

Video Enhancer V2 bundles a pinned LiteRT/TFLite build of **Real-ESRGAN General x4v3** for genuine learned x4 super-resolution.

- Model file: `realesr_general_x4v3.tflite`
- Distribution: `litert-community/real-esrgan-x4v3-litert`
- Pinned revision: `0a6fc35ba3e34e80b53f43089c1d2b612d33bea9`
- Download URL used by CI: `https://huggingface.co/litert-community/real-esrgan-x4v3-litert/resolve/0a6fc35ba3e34e80b53f43089c1d2b612d33bea9/realesr_general_x4v3.tflite`
- SHA-256: `6d597b46d812ada42bdde641ec6e78c0ef0b78ff5a825fcfd74b9a1b3bcbe9a4`
- Model license: BSD-3-Clause
- Input: `[1,128,128,3]` NHWC RGB float32, range 0..1
- Output: `[1,3,512,512]` NCHW RGB float32, range 0..1
- Scale: 4x

The GitHub Actions build downloads this exact pinned model and fails if its SHA-256 does not match before compiling the APK.

## Google AI Edge LiteRT reference implementation

The LiteRT compiled-model resource-management pattern and Real-ESRGAN tiling/inference structure were adapted from the public Google AI Edge `litert-samples` model-zoo implementation. Those reference files are licensed under Apache License 2.0; adapted source files in this repository retain attribution comments.

This project does not present deterministic Lanczos scaling as neural enhancement. `AI Detail` runs the Real-ESRGAN model; `Fast GPU` uses deterministic Media3 effects/resampling; `Smart AI` chooses between them based on the source and requested output.