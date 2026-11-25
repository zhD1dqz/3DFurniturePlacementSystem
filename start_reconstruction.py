"""One-click local entry: use the newest video from input/, reuse completed stages."""

import argparse
import hashlib
import json
import sys

import reconstruct as pipeline


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    incoming = pipeline.ROOT / "input"
    incoming.mkdir(exist_ok=True)
    videos = [path for path in incoming.iterdir() if path.is_file()
              and path.suffix.lower() in {".mp4", ".mov", ".m4v", ".mkv"}]
    if not videos:
        print(f"请先把家具原视频复制到：\n{incoming}\n\n然后重新双击“开始重建.cmd”，或直接告诉我视频路径。")
        return 2
    video = max(videos, key=lambda path: (path.stat().st_mtime_ns, path.name))
    config = json.loads((pipeline.ROOT / "tools.local.json").read_text(encoding="utf-8-sig"))
    tools = pipeline.Tools(config)
    report = pipeline.doctor(tools)
    pipeline.write_json(pipeline.ROOT / "environment-report.json", report)
    missing = [name for name, check in report["checks"].items() if not check["ready"]]
    if missing:
        raise pipeline.PipelineError("环境检查未通过：" + ", ".join(missing) + "。把这个提示告诉我即可。")
    fingerprint = {"video_sha256": pipeline.file_hash(video), "preset": pipeline.PRESET,
                   "trainer": pipeline.trainer_backend(tools)}
    key = hashlib.sha256(json.dumps(fingerprint, sort_keys=True).encode()).hexdigest()[:16]
    output = pipeline.ROOT / "runs" / f"furniture-{key}"
    print(f"使用视频：{video}\n结果目录：{output}\n电脑请保持接通电源，运行中不要关闭窗口。", flush=True)
    if len(videos) > 1:
        print("input 内有多个视频，本次使用修改时间最新的一个。", flush=True)
    args = argparse.Namespace(video=str(video), output=str(output), stop_after="train",
                              resume=(output / "run.json").is_file())
    state = pipeline.execute_pipeline(args, tools)
    print(f"\n模型已生成：{state['model_path']}\n将结果目录发给我，我会继续检查模型。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("已停止。再次双击会跳过已完成阶段。")
        raise SystemExit(130)
    except (pipeline.PipelineError, OSError, ValueError, KeyError) as exc:
        print(f"\n暂时没有完成：{exc}\n把此提示或 runs 内的日志路径告诉我，我来处理。", file=sys.stderr)
        raise SystemExit(1)
