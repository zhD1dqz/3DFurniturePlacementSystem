"""Behavioural tests; fake external programs do NOT validate reconstruction quality."""

import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

import reconstruct as pipeline


FIELDS = ["x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity",
          "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3"]


def workspace_temp():
    base = (pipeline.ROOT / "tests" / ".tmp").resolve()
    base.mkdir(parents=True, exist_ok=True)
    temporary = tempfile.TemporaryDirectory(dir=base)
    # Verify every target before TemporaryDirectory recursively cleans up its files.
    if not Path(temporary.name).resolve().is_relative_to(base):
        raise RuntimeError("Test cleanup path escaped its workspace")
    return temporary


def write_gaussian(path, truncated=False):
    header = "ply\nformat binary_little_endian 1.0\nelement vertex 2\n"
    header += "".join(f"property float {field}\n" for field in FIELDS)
    payload = b"\0" * len(FIELDS) * 4 * (1 if truncated else 2)
    path.write_bytes((header + "end_header\n").encode("ascii") + payload)


FAKE_PROGRAM = r'''
import json, pathlib, struct, sys
root = pathlib.Path(__file__).parent
controls = json.loads((root / 'controls.json').read_text())
name, args = sys.argv[1], sys.argv[2:]
with (root / 'calls.jsonl').open('a', encoding='utf-8') as stream:
    stream.write(json.dumps({'tool': name, 'args': args}, ensure_ascii=False) + '\n')
def arg(flag):
    return args[args.index(flag) + 1]
if controls.get('fail') == name and '-h' not in args:
    print('SIMULATED TOOL FAILURE', file=sys.stderr)
    sys.exit(9)
if name == 'ffprobe':
    print(json.dumps({'streams': [{'width': 1920, 'height': 1080}], 'format': {'duration': '45'}}))
elif name == 'ffmpeg':
    pattern = args[-1]
    for index in range(1, 101):
        pathlib.Path(pattern % index).write_bytes(b'FAKE_JPEG_FOR_WORKFLOW_TEST')
elif name == 'colmap':
    stage = args[0]
    if '-h' in args:
        prefix = 'Feature' if controls.get('new_colmap', True) else 'Sift'
        print('--' + prefix + ('Extraction' if stage == 'feature_extractor' else 'Matching') + '.use_gpu')
    elif stage == 'feature_extractor':
        pathlib.Path(arg('--database_path')).write_bytes(b'fake_db')
    elif stage == 'mapper':
        for folder, count in [('0', 40), ('1', controls.get('registered', 100))]:
            model = pathlib.Path(arg('--output_path')) / folder
            model.mkdir()
            for field, size in [('cameras', 1), ('images', count), ('points3D', 500)]:
                (model / (field + '.bin')).write_bytes(struct.pack('<Q', size))
    elif stage == 'image_undistorter':
        source = pathlib.Path(arg('--input_path'))
        target = pathlib.Path(arg('--output_path'))
        (target / 'sparse').mkdir()
        (target / 'images').mkdir()
        for field in ['cameras', 'images', 'points3D']:
            (target / 'sparse' / (field + '.bin')).write_bytes((source / (field + '.bin')).read_bytes())
        for image in pathlib.Path(arg('--image_path')).glob('*.jpg'):
            (target / 'images' / image.name).write_bytes(image.read_bytes())
'''


FAKE_TRAINER = r'''
import pathlib, sys
args = sys.argv[1:]
output = pathlib.Path(args[args.index('-m') + 1])
iteration = args[args.index('--iterations') + 1]
target = output / 'point_cloud' / ('iteration_' + iteration)
target.mkdir(parents=True)
fields = ['x','y','z','f_dc_0','f_dc_1','f_dc_2','opacity',
          'scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3']
header = 'ply\nformat binary_little_endian 1.0\nelement vertex 2\n'
header += ''.join('property float ' + field + '\n' for field in fields)
(target / 'point_cloud.ply').write_bytes((header + 'end_header\n').encode('ascii') + b'\0' * len(fields) * 4 * 2)
'''


class FakeTools:
    def __init__(self, folder):
        self.folder = folder

    def command(self, name):
        return [sys.executable, str(self.folder / "fake_tools.py"), name]

    def trainer(self):
        return self.folder, [sys.executable]


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = workspace_temp()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture = self.root / "fake programs"
        self.fixture.mkdir()
        (self.fixture / "fake_tools.py").write_text(FAKE_PROGRAM, encoding="utf-8")
        (self.fixture / "train.py").write_text(FAKE_TRAINER, encoding="utf-8")
        (self.fixture / "controls.json").write_text("{}", encoding="utf-8")
        self.video = self.root / "椅子 sample & video.mp4"
        self.video.write_bytes(b"simulated_video_input")
        self.output = self.root / "result with spaces"
        self.tools = FakeTools(self.fixture)

    def args(self, stop_after="train", resume=False):
        return argparse.Namespace(video=str(self.video), output=str(self.output),
                                  stop_after=stop_after, resume=resume)

    def run_pipeline(self, args):
        with redirect_stdout(io.StringIO()), patch.object(pipeline, "training_check", return_value="SIMULATED CUDA"):
            return pipeline.execute_pipeline(args, self.tools)

    def controls(self, **kwargs):
        (self.fixture / "controls.json").write_text(json.dumps(kwargs), encoding="utf-8")

    def calls(self):
        return [json.loads(line) for line in (self.fixture / "calls.jsonl").read_text(encoding="utf-8").splitlines()]

    def test_pipeline_outputs_gaussian_and_chooses_largest_sparse_model(self):
        state = self.run_pipeline(self.args())
        self.assertEqual(state["status"], "completed")
        self.assertEqual(state["metrics"]["selected_sparse_model"], "1")
        self.assertEqual(state["metrics"]["registered_frames"], 100)
        self.assertEqual(pipeline.gaussian_info(self.output / "model.ply")["gaussian_count"], 2)
        self.assertTrue((self.output / "dataset/sparse/0/cameras.bin").is_file())
        ffmpeg = next(call for call in self.calls() if call["tool"] == "ffmpeg")
        self.assertIn(str(self.video), ffmpeg["args"])
        self.assertFalse((self.output / ".run.lock").exists())

    def test_resume_skips_completed_frames(self):
        first = self.run_pipeline(self.args("frames"))
        self.assertEqual(first["status"], "ready")
        result = self.run_pipeline(self.args(resume=True))
        self.assertEqual(result["status"], "completed")
        self.assertEqual(sum(c["tool"] == "ffmpeg" for c in self.calls()), 1)

    def test_resume_rejects_changed_video(self):
        self.run_pipeline(self.args("frames"))
        self.video.write_bytes(b"a_different_video")
        with self.assertRaisesRegex(pipeline.PipelineError, "视频内容"):
            self.run_pipeline(self.args(resume=True))

    def test_failure_is_reported_and_resume_uses_a_new_stage_attempt(self):
        self.controls(fail="colmap")
        with self.assertRaisesRegex(pipeline.PipelineError, "退出码 9"):
            self.run_pipeline(self.args())
        state = json.loads((self.output / "run.json").read_text(encoding="utf-8"))
        self.assertEqual(state["status"], "failed")
        self.assertEqual(state["completed_stages"], ["frames"])
        self.assertFalse((self.output / "model.ply").exists())
        self.controls()
        result = self.run_pipeline(self.args(resume=True))
        self.assertEqual(result["status"], "completed")
        self.assertEqual(len(result["attempts"]), 2)

    def test_low_registration_blocks_training(self):
        self.controls(registered=60)
        with self.assertRaisesRegex(pipeline.PipelineError, "75%"):
            self.run_pipeline(self.args())
        self.assertFalse((self.output / "model.ply").exists())
        self.assertFalse(any((self.output / "work").glob("*/gaussian")))

    def test_existing_files_are_preserved(self):
        self.output.mkdir()
        sentinel = self.output / "keep.txt"
        sentinel.write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(pipeline.PipelineError, "非空"):
            self.run_pipeline(self.args())
        self.assertEqual(sentinel.read_text(), "keep")

    def test_old_colmap_option_names(self):
        self.controls(new_colmap=False)
        self.run_pipeline(self.args("colmap"))
        commands = self.calls()
        extraction = next(c for c in commands if "--database_path" in c["args"] and c["args"][0] == "feature_extractor")
        self.assertIn("--SiftExtraction.use_gpu", extraction["args"])
        matching = next(c for c in commands if "--database_path" in c["args"] and c["args"][0] == "exhaustive_matcher")
        self.assertIn("--SiftMatching.use_gpu", matching["args"])

    def test_concurrent_run_is_rejected(self):
        self.output.mkdir()
        with pipeline.run_lock(self.output):
            with self.assertRaisesRegex(pipeline.PipelineError, "运行锁"):
                with pipeline.run_lock(self.output):
                    self.fail("Must not acquire the same lock twice")

    def test_missing_trained_model_is_not_marked_completed(self):
        (self.fixture / "train.py").write_text("print('no model generated')", encoding="utf-8")
        with self.assertRaisesRegex(pipeline.PipelineError, "未生成最终模型"):
            self.run_pipeline(self.args())
        state = json.loads((self.output / "run.json").read_text(encoding="utf-8"))
        self.assertEqual(state["status"], "failed")
        self.assertNotIn("train", state["completed_stages"])

    def test_resume_detects_lost_artifacts(self):
        self.run_pipeline(self.args("frames"))
        for image in (self.output / "frames").glob("*.jpg"):
            image.unlink()
        with self.assertRaisesRegex(pipeline.PipelineError, "产物丢失"):
            self.run_pipeline(self.args(resume=True))

    def test_brush_backend_exports_and_validates_final_model(self):
        self.tools.config = {"trainer": "brush"}
        program = self.fixture / "fake_tools.py"
        program.write_text(program.read_text(encoding="utf-8") + r'''
elif name == 'brush':
    if '--version' in args:
        print('brush-cli 0.3.0')
    elif not controls.get('no_brush_export'):
        target = pathlib.Path(arg('--export-path')) / ('model_' + arg('--total-steps') + '.ply')
        fields = ['x','y','z','f_dc_0','f_dc_1','f_dc_2','opacity',
                  'scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3']
        header = 'ply\nformat binary_little_endian 1.0\nelement vertex 2\n'
        header += ''.join('property float ' + field + '\n' for field in fields)
        target.write_bytes((header + 'end_header\n').encode() + b'\0' * len(fields) * 4 * 2)
''', encoding="utf-8")
        state = self.run_pipeline(self.args())
        self.assertEqual(state["metrics"]["gaussian_count"], 2)
        command = next(c["args"] for c in self.calls() if c["tool"] == "brush" and "--total-steps" in c["args"])
        self.assertEqual(command[command.index("--total-steps") + 1], "7000")
        self.assertEqual(state["fingerprint"]["trainer"], "brush")

        # A successful process exit without the final export must still fail.
        self.output = self.root / "missing-brush-output"
        self.controls(no_brush_export=True)
        with self.assertRaisesRegex(pipeline.PipelineError, "未生成最终模型"):
            self.run_pipeline(self.args())
        state = json.loads((self.output / "run.json").read_text(encoding="utf-8"))
        self.assertNotIn("train", state["completed_stages"])

    def test_resume_rejects_changed_trainer(self):
        self.run_pipeline(self.args("frames"))
        self.tools.config = {"trainer": "brush"}
        with self.assertRaisesRegex(pipeline.PipelineError, "固定参数"):
            self.run_pipeline(self.args(resume=True))


class ModelValidationTests(unittest.TestCase):
    def test_truncated_gaussian_rejected(self):
        with workspace_temp() as directory:
            path = Path(directory) / "model.ply"
            write_gaussian(path, truncated=True)
            with self.assertRaisesRegex(pipeline.PipelineError, "数据不完整"):
                pipeline.gaussian_info(path)

    def test_ordinary_point_cloud_rejected(self):
        with workspace_temp() as directory:
            path = Path(directory) / "points.ply"
            path.write_bytes(b"ply\nformat binary_little_endian 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nend_header\n" + b"\0" * 12)
            with self.assertRaisesRegex(pipeline.PipelineError, "Gaussian 属性"):
                pipeline.gaussian_info(path)


if __name__ == "__main__":
    unittest.main()
