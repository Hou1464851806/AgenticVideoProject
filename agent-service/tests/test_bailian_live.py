"""Real Beijing provider boundary; credentials and media never appear in output."""
import os
import json
import hashlib
import time
import unittest
from pathlib import Path

from agent_service.analysis.bailian import BailianAsr


@unittest.skipUnless(os.environ.get("RUN_LIVE_ASR") == "1" and os.environ.get("DASHSCOPE_API_KEY"), "Explicit RUN_LIVE_ASR=1 and credentials required")
class BailianLiveTest(unittest.TestCase):
    def test_real_local_audio_returns_sentence_and_word_timestamps(self):
        audio = Path(os.environ["ASR_TEST_AUDIO"])
        adapter = BailianAsr(os.environ["DASHSCOPE_API_KEY"])
        receipt = Path(os.environ.get("ASR_TEST_RECEIPT", ".scratch/asr-fixture/live-receipt.json"))
        audio_hash = hashlib.sha256(audio.read_bytes()).hexdigest()
        saved = json.loads(receipt.read_text()) if receipt.exists() else {}
        if saved.get("audioHash") == audio_hash:
            task_id = saved["taskId"]
        else:
            address = adapter.upload(audio)
            accepted = adapter.submit(address)
            task_id = accepted["output"]["task_id"]
            receipt.write_text(json.dumps({"taskId": task_id, "audioHash": audio_hash}))
        self.assertTrue(task_id)
        deadline = time.monotonic() + 180
        while True:
            result = adapter.query(task_id)
            status = result["output"]["task_status"]
            if status in ("SUCCEEDED", "FAILED", "UNKNOWN"):
                break
            self.assertLess(time.monotonic(), deadline, "Provider task timed out; query saved task ID before resubmitting")
            time.sleep(2)
        self.assertEqual(status, "SUCCEEDED")
        transcript = adapter.transcript(result)
        receipt.write_text(json.dumps({"taskId": task_id, "audioHash": audio_hash, "usage": result.get("usage"),
            "transcripts": transcript["transcripts"]}, ensure_ascii=False))
        sentences = transcript["transcripts"][0]["sentences"]
        self.assertIn("上传素材", "".join(s["text"] for s in sentences))
        self.assertIn("核查原文", "".join(s["text"] for s in sentences))
        self.assertGreater(len(sentences), 1)
        for sentence in sentences:
            self.assertGreaterEqual(sentence["begin_time"], 0)
            self.assertGreater(sentence["end_time"], sentence["begin_time"])
            self.assertTrue(sentence["words"])
            for word in sentence["words"]:
                self.assertGreaterEqual(word["begin_time"], sentence["begin_time"])
                self.assertLessEqual(word["end_time"], sentence["end_time"])
        self.assertIn("usage", result)


if __name__ == "__main__":
    unittest.main()
