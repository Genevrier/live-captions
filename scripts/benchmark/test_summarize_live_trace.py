import json
import io
import tempfile
import unittest
from pathlib import Path

from summarize_live_trace import main, records_from_log, summarize_session


class LiveTraceSummaryTest(unittest.TestCase):
    def test_summarizes_samples_events_and_qnn_phases_separately(self):
        lines = [
            "09-25 10:00:00.000 I/LiveCaptionsRun( 1234): session=7 atNs=1000 elapsedNs=0 state=LISTENING "
            "audioHz=16000 audioSamples=1600 firstUsefulMs=-1 captureBacklogMs=0 captionBacklogMs=5 "
            "appGroupPssKb=100000 qnnPssKb=0 qnnPresent=false droppedAudioMs=0 skippedTranslations=0 thermal=None",
            "09-25 10:00:05.000 I/LiveCaptionsRun( 1234): session=7 atNs=5000001000 elapsedNs=5000000000 state=LISTENING "
            "audioHz=16000 audioSamples=80000 firstUsefulMs=900 captureBacklogMs=10 captionBacklogMs=15 "
            "appGroupPssKb=120000 qnnPssKb=20000 qnnPresent=true droppedAudioMs=20 skippedTranslations=1 thermal=Moderate",
            "09-25 10:00:05.100 D/CaptionPipeline( 1234): session=7 atNs=5100000000 event=hy-started queueWaitMs=25",
            "09-25 10:00:06.000 D/CaptionPipeline( 1234): session=7 atNs=6000000000 event=hy-displayed-to-state "
            "computeMs=850 displayMs=3 final=true accepted=true firstVisibleMs=200 completeMs=850",
            "09-25 10:00:06.100 I/LiveCaptionsBenchmark( 1234): qnn_profile session=7 calls=64 init_ns=500000000 "
            "library_ns=10000000 dsp_copy_ns=20000000 dsp_setup_ns=30000000 recognizer_prepare_ns=40000000 "
            "rpc_p50_ns=2000000 rpc_p95_ns=4000000 service_p50_ns=1000000 service_p95_ns=2000000 "
            "binder_p50_ns=1000000 binder_p95_ns=2000000 feed_ns=10000000 combined_decode_ns=90000000 "
            "result_ns=3000000 endpoint_ns=1000000 graph_breakdown=unavailable",
            "09-25 10:00:06.200 I/OtherTag( 1234): session=7 event=ignored",
        ]
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "device.log"
            log.write_text("\n".join(lines), encoding="utf-8")
            sessions, parsed = records_from_log(log)
        self.assertEqual(parsed, 5)
        result = summarize_session(sessions["7"])
        self.assertEqual(result["samples"], 2)
        self.assertEqual(result["events"], 2)
        self.assertEqual(result["firstUsefulSubtitleMs"], 900)
        self.assertEqual(result["eventCounts"]["hy-started"], 1)
        self.assertEqual(result["eventLatencyMs"]["hy-displayed-to-state.computeMs"]["p95"], 850)
        self.assertEqual(result["sampleDistributions"]["appGroupPssKb"]["max"], 120000)
        self.assertEqual(result["droppedAudioMsFinal"], 20)
        self.assertEqual(result["qnnCallsAtProfile"], 64)
        self.assertEqual(result["qnnProfileMs"]["combinedDecodeMs"]["p50"], 90)
        self.assertEqual(result["qnnGraphBreakdown"], ["unavailable"])

    def test_cli_writes_machine_readable_report(self):
        line = "I/LiveCaptionsRun(1): session=abc atNs=10 elapsedNs=0 state=LISTENING audioHz=16000\n"
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder) / "run.log"
            output = Path(folder) / "summary.json"
            log.write_text(line, encoding="utf-8")
            import sys
            original = sys.argv
            original_stdout = sys.stdout
            try:
                sys.argv = ["summarize_live_trace.py", str(log), "--json", str(output)]
                sys.stdout = io.StringIO()
                self.assertEqual(main(), 0)
            finally:
                sys.argv = original
                sys.stdout = original_stdout
            self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["schema"],
                             "live-captions-logcat-summary-v1")


if __name__ == "__main__":
    unittest.main()
