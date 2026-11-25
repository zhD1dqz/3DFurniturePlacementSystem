"""Handoff tests: reject the wrong capture/changed asset before packaging."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import furniture_workflow as flow
import reconstruct as pipeline
from test_reconstruct import write_gaussian


class LocalWorkflowTests(unittest.TestCase):
    def setUp(self):
        base = flow.ROOT / "tests/.tmp"
        base.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=base)
        self.assertTrue(Path(temporary.name).resolve().is_relative_to(base.resolve()))
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for attr, value in (("ROOT", self.root), ("TASKS", self.root / "tasks")):
            patcher = patch.object(flow, attr, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        (self.root / "tools.local.json").write_text('{"trainer":"brush"}')
        self.video = self.root / "家具 & sample.mp4"
        self.video.write_bytes(b"source-video")
        self.record = flow.new_task(self.video, "椅子", 80)
        self.task_id = self.record["id"]

    def reconstructed(self):
        run = Path(self.record["run"])
        run.mkdir(parents=True)
        write_gaussian(run / "model.ply")
        write_gaussian(run / "chair.ply")
        flow.save(run / "run.json", {"status": "completed", "completed_stages": ["frames", "colmap", "train"],
                  "fingerprint": {"video_sha256": self.record["video_sha256"]}})
        folder = self.root / "asset"
        folder.mkdir()
        write_gaussian(folder / "chair.ply")
        flow.save(folder / "calibration.json", {"source_sha256": pipeline.file_hash(folder / "chair.ply")})
        return folder

    def test_task_keeps_verified_local_video_and_reuses_identical_input(self):
        self.assertEqual(Path(self.record["video"]).read_bytes(), self.video.read_bytes())
        self.video.unlink()
        flow.check_video(self.record)
        reused = flow.new_task(self.record["video"], "再次选择")
        self.assertEqual(reused["id"], self.task_id)
        self.assertEqual(reused["name"], "椅子")

    def test_changed_task_video_cannot_reuse_results(self):
        Path(self.record["video"]).write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "视频已变化"):
            flow.check_video(self.record)

    def test_unreviewed_asset_blocks_build_before_external_programs(self):
        with patch.object(flow.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "尚未审核"):
                flow.package_task(self.task_id)
            command.assert_not_called()
        self.assertIn("尚未审核", flow.load(self.task_id)["error"])

    def test_changed_calibration_blocks_package(self):
        folder = self.reconstructed()
        flow.approve_asset(self.task_id, folder, "人工检查通过")
        (folder / "calibration.json").write_text(json.dumps({"source_sha256": pipeline.file_hash(folder / "chair.ply"), "scale": 2}))
        with patch.object(flow.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "资产发生变化"):
                flow.package_task(self.task_id)
            command.assert_not_called()

    def test_asset_from_another_capture_is_rejected(self):
        folder = self.reconstructed()
        with (folder / "chair.ply").open("ab") as stream: stream.write(b"another")
        flow.save(folder / "calibration.json", {"source_sha256": pipeline.file_hash(folder / "chair.ply")})
        with self.assertRaisesRegex(ValueError, "不属于本任务"):
            flow.approve_asset(self.task_id, folder, "检查")

    def test_masks_from_another_video_are_rejected_before_extraction(self):
        self.reconstructed()
        masks = self.root / "wrong-masks.json"
        flow.save(masks, {"source_run": "runs/some-other-video"})
        with self.assertRaisesRegex(ValueError, "另一段视频"):
            flow.prepare_task(self.task_id, masks, 80)
        self.assertNotIn("candidate_asset", flow.load(self.task_id))

    def test_modified_package_cannot_be_installed(self):
        folder = self.reconstructed()
        flow.approve_asset(self.task_id, folder, "检查通过")
        apk = self.root / "package.apk"
        apk.write_bytes(b"original")
        with flow.edit(self.task_id) as record:
            record["package"] = {"apk": str(apk), "sha256": pipeline.file_hash(apk)}
        apk.write_bytes(b"modified")
        with patch.object(flow.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "发生变化"):
                flow.install_task(self.task_id)
            command.assert_not_called()

    def test_invalid_task_path_and_nonfinite_height_are_rejected(self):
        for task_id in ("../outside", "a/b", "a\\b"):
            with self.assertRaises(ValueError): flow.task_dir(task_id)
        with self.assertRaises(ValueError): flow.new_task(self.video, "家具", float("nan"))


if __name__ == "__main__":
    unittest.main()
