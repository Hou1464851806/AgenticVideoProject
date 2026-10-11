# Audio and video fixtures

`human-speech.mp4` derives from the Google FLEURS dataset, Chinese test sample `10026684690566417990.wav` (utterance ID 1906, male speaker).

- Source: [Google FLEURS](https://huggingface.co/datasets/google/fleurs), `data/cmn_hans_cn/audio/test.tar.gz`.
- Authors: Alexis Conneau, Min Ma, Simran Khanuja, Yu Zhang, Vera Axelrod, Siddharth Dalmia, Jason Riesa, Clara Rivera, and Ankur Bapna. *FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech*, 2022. [Paper](https://arxiv.org/abs/2205.12446).
- License: [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
- Changes: one recording extracted, converted to 16 kHz mono AAC, combined with an 11-second solid blue H.264 video. Original speech is unchanged except audio encoding. No endorsement is implied.
- Human reference transcription: “这并不是告别。这是一个篇章的结束，也是新篇章的开始。”

`labelled-speech.mp4` is generated with the macOS Eddy Chinese voice and combined with a green H.264 video. Its known script and independently measured stream start times are recorded in `labelled-speech.json`. It is a transfer and timing fixture, not a natural speaker accuracy benchmark.

Other video fixtures are locally generated FFmpeg test patterns and tones.

`labelled-vision-vfr.mp4` is a locally drawn red/blue rectangle fixture with Noto Sans CJK SC text (SIL Open Font License 1.1), encoded losslessly with irregular frame PTS. Its independently measured frame times and manually assigned scene/text labels are in `labelled-vision-vfr.json`. No audio is present.

`speech-mention-only.mp4` uses the same red rectangle / 证据42 image and macOS Eddy zh_CN generated speech: “视频里提到一只猫，但当前画面只有红色矩形。” The image contains no cat. It verifies that audio mentions do not become visual observations; it is not a natural speech accuracy benchmark.

`brief-small-text.mp4` is a locally drawn white/red rectangle fixture with 16-pixel Noto Sans CJK SC text (SIL Open Font License 1.1), visible from 900 through 1350 ms at 20 fps. Labels in `brief-small-text.json` were assigned from the drawing and timeline before model calls. It contains no airplane and no audio; it verifies bounded inspection of transient small text, not general OCR accuracy.
