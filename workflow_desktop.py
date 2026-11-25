"""Small offline desktop front end. Expensive work runs in a separate process."""
from datetime import datetime
import os
from pathlib import Path
import subprocess
import sys
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import furniture_workflow as flow


class WorkflowWindow:
    def __init__(self, root):
        self.root = root
        self.process = None
        self.log_stream = None
        self.log_path = None
        self.root.title("家具重建 · 本地流程")
        self.root.geometry("1000x760")
        self.root.minsize(850, 640)
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TLabel", font=("Microsoft YaHei UI", 10))
        style.configure("TButton", font=("Microsoft YaHei UI", 10), padding=8)
        style.configure("Treeview", font=("Microsoft YaHei UI", 10), rowheight=32)
        outer = ttk.Frame(root, padding=20)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="家具视频 → 手机 AR", font=("Microsoft YaHei UI", 22, "bold")).pack(anchor="w")
        ttk.Label(outer, text="视频留在电脑本地。先完成重建与家具检查，再生成安装包，最后用手机验证。").pack(anchor="w", pady=(6, 16))
        top = ttk.Frame(outer)
        top.pack(fill="x")
        self.selection = tk.StringVar()
        self.tasks = ttk.Combobox(top, textvariable=self.selection, state="readonly", width=42)
        self.tasks.pack(side="left", fill="x", expand=True)
        self.tasks.bind("<<ComboboxSelected>>", lambda _: self.refresh_details())
        self.new_button = ttk.Button(top, text="选择视频，创建任务", command=self.new_dialog)
        self.new_button.pack(side="left", padx=(12, 0))
        self.demo_button = ttk.Button(top, text="载入现有椅子样例", command=lambda: self.start("demo"))
        self.demo_button.pack(side="left", padx=(8, 0))
        self.summary = ttk.Label(outer, text="请选择视频或载入样例", wraplength=920)
        self.summary.pack(anchor="w", pady=12)
        self.tree = ttk.Treeview(outer, columns=("stage", "state"), show="headings", height=8)
        self.tree.heading("stage", text="处理步骤")
        self.tree.heading("state", text="当前状态")
        self.tree.column("stage", width=500)
        self.tree.column("state", width=250)
        self.tree.pack(fill="x")
        actions = ttk.Frame(outer)
        actions.pack(fill="x", pady=12)
        self.run_button = ttk.Button(actions, text="开始 / 继续重建", command=lambda: self.for_task("reconstruct"))
        self.pack_button = ttk.Button(actions, text="生成手机安装包", command=lambda: self.for_task("package"))
        self.install_button = ttk.Button(actions, text="安装到小米手机", command=lambda: self.for_task("install"))
        self.preview_button = ttk.Button(actions, text="预览模型", command=self.preview)
        for button in (self.run_button, self.pack_button, self.install_button, self.preview_button): button.pack(side="left", padx=(0, 8))
        ttk.Button(actions, text="打开任务文件夹", command=self.open_folder).pack(side="right")
        self.next_step = ttk.Label(outer, text="", wraplength=920)
        self.next_step.pack(anchor="w", pady=(0, 10))
        ttk.Label(outer, text="处理记录（重建时请保持电脑接通电源）").pack(anchor="w")
        self.log = tk.Text(outer, height=9, font=("Microsoft YaHei UI", 9), wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True, pady=(6, 0))
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.refresh_tasks()
        self.tick()

    def selected_id(self):
        return self.task_map.get(self.selection.get())

    def refresh_tasks(self, prefer_new=False):
        current = self.selected_id() if hasattr(self, "task_map") else None
        previous = set(self.task_map.values()) if hasattr(self, "task_map") else set()
        records = []
        for path in sorted(flow.TASKS.glob("*/task.json"), reverse=True):
            try: records.append(flow.read_json(path))
            except (OSError, ValueError): pass
        self.task_map = {r["name"] + " · " + r["id"][-8:]: r["id"] for r in records}
        self.tasks["values"] = list(self.task_map)
        matching = [label for label, task_id in self.task_map.items() if task_id == current]
        additions = [label for label, task_id in self.task_map.items() if task_id not in previous]
        if prefer_new and additions: self.selection.set(additions[0])
        elif matching: self.selection.set(matching[0])
        elif self.task_map: self.selection.set(next(iter(self.task_map)))
        self.refresh_details()

    def refresh_details(self):
        task_id = self.selected_id()
        record = flow.load(task_id) if task_id else None
        self.tree.delete(*self.tree.get_children())
        if record:
            height = f"{record['height_cm']:g} cm" if record.get("height_cm") else "待填写（样例以手机已确认尺寸为准）"
            self.summary.config(text=f"{record['name']}   |   实测高度：{height}\n任务编号：{task_id}")
            for stage, state in flow.status(record): self.tree.insert("", "end", values=(stage, state))
            if record.get("error"): hint = "上次未完成：" + record["error"]
            elif record.get("phone_test"): hint = "手机检查：确认实测尺寸后放置，绕着查看；用前景实物测试遮挡，可勾选深度图展示。"
            elif record.get("package"): hint = "下一步：用数据线连接小米手机，允许 USB 调试，然后点击安装。新安装包仍需手机实测。"
            elif record.get("asset"): hint = "家具检查已登记，可以生成该任务的安装包。"
            else: hint = "重建后把任务编号告诉我：我负责标注提取、检查朝向与地面、校准尺寸，再开放打包。无需你手动操作建模软件。"
            self.next_step.config(text=hint)
        busy = self.process is not None
        self.run_button.config(state="normal" if record and not busy else "disabled")
        self.pack_button.config(state="normal" if record and record.get("asset") and not busy else "disabled")
        self.install_button.config(state="normal" if record and record.get("package") and not busy else "disabled")
        model = Path(record["asset"]["folder"]) / "chair.ply" if record and record.get("asset") else Path(record["run"]) / "model.ply" if record else None
        self.preview_button.config(state="normal" if model and model.exists() and not busy else "disabled")
        for button in (self.new_button, self.demo_button): button.config(state="disabled" if busy else "normal")

    def new_dialog(self):
        video = filedialog.askopenfilename(title="选择家具原视频", filetypes=[("家具视频", "*.mp4 *.mov *.m4v *.mkv"), ("所有文件", "*.*")])
        if not video: return
        dialog = tk.Toplevel(self.root)
        dialog.title("新家具任务")
        dialog.transient(self.root)
        dialog.grab_set()
        form = ttk.Frame(dialog, padding=20)
        form.pack(fill="both", expand=True)
        ttk.Label(form, text="家具名称").pack(anchor="w")
        name = ttk.Entry(form, width=38)
        name.insert(0, Path(video).stem)
        name.pack(fill="x", pady=(4, 12))
        ttk.Label(form, text="实测总高（厘米，可先留空）").pack(anchor="w")
        height = ttk.Entry(form)
        height.pack(fill="x", pady=(4, 12))
        def create():
            args = ["new", "--video", video, "--name", name.get()]
            if height.get().strip():
                try:
                    number = float(height.get())
                    if not 5 <= number <= 1000: raise ValueError()
                except ValueError:
                    messagebox.showerror("检查高度", "请输入 5～1000 cm，或留空", parent=dialog)
                    return
                args += ["--height-cm", str(number)]
            if not name.get().strip(): return
            dialog.destroy()
            self.start(*args)
        ttk.Button(form, text="保存视频并创建任务", command=create).pack(anchor="e")

    def for_task(self, action):
        task_id = self.selected_id()
        if task_id: self.start(action, task_id)

    def start(self, *arguments):
        if self.process: return
        logs = flow.ROOT / "workflow-logs"
        logs.mkdir(exist_ok=True)
        self.log_path = logs / (datetime.now().strftime("%Y%m%d_%H%M%S_%f") + ".log")
        self.log_stream = self.log_path.open("w", encoding="utf-8")
        self.process = subprocess.Popen([sys.executable, "-u", str(flow.ROOT / "furniture_workflow.py"), *arguments],
                                        cwd=flow.ROOT, stdout=self.log_stream, stderr=subprocess.STDOUT,
                                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.refresh_details()

    def open_folder(self):
        path = flow.task_dir(self.selected_id()) if self.selected_id() else flow.ROOT
        os.startfile(str(path))

    def preview(self):
        record = flow.load(self.selected_id())
        model = Path(record["asset"]["folder"]) / "chair.ply" if record.get("asset") else Path(record["run"]) / "model.ply"
        try:
            subprocess.Popen([str(flow.ROOT / "tools/brush/brush_app.exe"), str(model), "--with-viewer"], cwd=flow.ROOT)
        except OSError as exc:
            messagebox.showerror("暂时无法预览", str(exc))

    def tick(self):
        if self.process:
            exit_code = self.process.poll()
            if self.log_path:
                text = self.log_path.read_text(encoding="utf-8", errors="replace")[-14000:]
                self.log.config(state="normal")
                self.log.delete("1.0", "end")
                self.log.insert("end", text)
                self.log.see("end")
                self.log.config(state="disabled")
            if exit_code is not None:
                self.process = None
                self.log_stream.close()
                self.refresh_tasks(prefer_new=True)
                if exit_code: messagebox.showerror("本步尚未完成", "处理记录已保留，请把下方提示告诉我。可以修复后继续。")
            else: self.refresh_details()
        else:
            self.refresh_tasks()
        self.root.after(1500, self.tick)

    def close(self):
        if self.process:
            messagebox.showinfo("正在处理", "请等待本步结束再关闭，避免中断重建。")
            return
        self.root.destroy()


if __name__ == "__main__":
    window = tk.Tk()
    WorkflowWindow(window)
    window.mainloop()
