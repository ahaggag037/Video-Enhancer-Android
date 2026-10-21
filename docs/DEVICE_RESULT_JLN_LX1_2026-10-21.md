# HUAWEI JLN-LX1 — NanoVSR GPU Results — 2026-10-21

Device evidence supplied from the real Android diagnostic APK:

- Device: HUAWEI JLN-LX1
- Android: 12 / API 31
- Hardware: qcom / QTI SM6225
- Tested runtime: LiteRT `CompiledModel` GPU
- Thermal status remained `0 -> 0` during the short benchmark.

## Unmodified NanoVSR

- GPU compile: FAIL
- CPU: ~22,479.9 ms per 5-frame window (~0.2 fps)
- Decision: reject unmodified graph and CPU fallback.

## Operator isolation

PASS:
- Conv + LeakyReLU
- PReLU
- Bilinear Resize
- Concat + 1x1 Conv

FAIL:
- PixelShuffle / DepthToSpace
- rank-5 temporal gather

Conclusion: the first GPU failure was graph/operator compatibility, not image size.

## Exact GPU-safe NanoVSR rewrite

Rewrite preserved the released NanoVSR-226k weights and recurrence while replacing:
- rank-5 temporal input/gathers with five explicit 4D frame inputs;
- PixelShuffle with an exact one-hot ConvTranspose representation and then an exact GPU-safe zero-stuff / resize / Conv2D lowering.

Offline parity before APK build:
- max absolute error: ~7.15e-7
- mean absolute error: ~7.17e-8

Real-device results:

| Input window | Output | Compile | Invoke | Model-only effective fps |
|---|---:|---:|---:|---:|
| 5x32x32 | 5x128x128 | 704 ms | 305.9 ms | 16.3 fps |
| 5x64x64 | 5x256x256 | 501 ms | 1152.0 ms | 4.3 fps |
| 5x96x160 | 5x384x640 | 587 ms | 4110.4 ms | 1.2 fps |
| 5x180x320 | 5x720x1280 | 1021 ms | 5178.5 ms | 1.0 fps |

## Architectural conclusion

The exact rewrite proves the Adreno GPU can execute NanoVSR after the two incompatible graph patterns are removed. However, the complete learned x4 reconstruction head remains far too expensive for long-video product use on this device.

Therefore the next gate removes the neural x4 HR head and measures only the LR temporal brain:

1. bidirectional 5-frame temporal core -> center-frame LR fused features;
2. streaming forward core -> one LR frame + hidden state -> next hidden state.

If the streaming LR core is practical, V3 should keep neural work at low resolution and move final reconstruction/upscale into a fused deterministic GPU compositor. The product path remains blocked until this next device benchmark is measured.
