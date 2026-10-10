# Audio and video fixtures

`human-speech.mp4` derives from the Google FLEURS dataset, Chinese test sample `10026684690566417990.wav` (utterance ID 1906, male speaker).

- Source: [Google FLEURS](https://huggingface.co/datasets/google/fleurs), `data/cmn_hans_cn/audio/test.tar.gz`.
- Authors: Alexis Conneau, Min Ma, Simran Khanuja, Yu Zhang, Vera Axelrod, Siddharth Dalmia, Jason Riesa, Clara Rivera, and Ankur Bapna. *FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech*, 2022. [Paper](https://arxiv.org/abs/2205.12446).
- License: [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
- Changes: one recording extracted, converted to 16 kHz mono AAC, combined with an 11-second solid blue H.264 video. Original speech is unchanged except audio encoding. No endorsement is implied.
- Human reference transcription: “这并不是告别。这是一个篇章的结束，也是新篇章的开始。”

`labelled-speech.mp4` is generated with the macOS Eddy Chinese voice and combined with a green H.264 video. Its known script and independently measured stream start times are recorded in `labelled-speech.json`. It is a transfer and timing fixture, not a natural speaker accuracy benchmark.

Other video fixtures are locally generated FFmpeg test patterns and tones.
