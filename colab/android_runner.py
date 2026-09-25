"""Headless Android build/test rounds for the Colab notebook (stdlib only).

The notebook embeds this file, so uploading the notebook does not depend on a
previously pushed version of the runner. All subprocesses have deadlines and
every round owns its APKs, logs and device artifacts.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import signal
import socket
import subprocess
import tarfile
import time
import zipfile


PACKAGE = "ml.melun.mangaview"
INSTRUMENTATION = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
DEFAULT_TESTS = ",".join((
    "ml.melun.mangaview.viewer.runtime.NativeEngineImageDecoderTest",
    "ml.melun.mangaview.app.EngineOfflineReaderDeviceTest",
))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def stop_process(process):
    if process is None or process.poll() is not None:
        return
    if os.name == "posix":
        os.killpg(process.pid, signal.SIGTERM)
    else:
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.wait(timeout=10)


def command(args, log, *, cwd=None, env=None, timeout=120, stdin=None):
    """Save output even on timeout; an adb exit code alone is not a test verdict."""
    log = Path(log)
    log.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    result = {"command": [str(a) for a in args], "status": "ERROR", "returncode": None}
    with log.open("wb") as output:
        try:
            process = subprocess.Popen(
                [str(a) for a in args], cwd=cwd, env=env,
                stdin=subprocess.PIPE if stdin is not None else subprocess.DEVNULL,
                stdout=output, stderr=subprocess.STDOUT,
                start_new_session=os.name == "posix",
            )
            try:
                process.communicate(input=stdin, timeout=timeout)
                result.update(returncode=process.returncode,
                              status="OK" if process.returncode == 0 else "FAILED")
            except subprocess.TimeoutExpired:
                stop_process(process)
                result.update(status="TIMEOUT", returncode=process.returncode)
                output.write(b"\nHOST COMMAND TIMEOUT\n")
            except BaseException:
                stop_process(process)
                raise
        except OSError as error:
            output.write(str(error).encode("utf-8"))
            result["error"] = str(error)
    result["seconds"] = round(time.monotonic() - started, 3)
    write_json(log.with_suffix(log.suffix + ".json"), result)
    return result


def instrumentation_result(output, exit_code=0, timed_out=False):
    """Reject crashes, empty selections, ignored tests and incomplete output."""
    failures = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-\d+)\s*$", output, re.M)
    completed = len(re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*0\s*$", output, re.M))
    expected = [int(n) for n in re.findall(r"^INSTRUMENTATION_STATUS: numtests=(\d+)", output, re.M)]
    ok = re.search(r"^OK \((\d+) tests?\)\s*$", output, re.M)
    final = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.M)
    errors = any(marker in output for marker in (
        "FAILURES!!!", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
        "Process crashed", "shortMsg=", "Error: Unable to find instrumentation",
    ))
    count = int(ok.group(1)) if ok else 0
    passed = (exit_code == 0 and not timed_out and not errors and not failures
              and count > 0 and completed == count and final == ["-1"]
              and bool(expected) and max(expected) == count)
    return {"status": "PASSED" if passed else "FAILED", "passed_tests": completed,
            "expected_tests": max(expected, default=0), "reported_tests": count,
            "timed_out": timed_out, "failure_codes": failures}


def safe_extract_zip(archive, destination):
    destination = Path(destination).resolve()
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            name = entry.filename.replace("\\", "/")
            target = (destination / name).resolve()
            mode = entry.external_attr >> 16
            if (not target.is_relative_to(destination) or name.startswith("/")
                    or ":" in name or (mode & 0o170000) == 0o120000):
                raise ValueError(f"Unsafe archive member: {entry.filename}")
        bundle.extractall(destination)


@dataclasses.dataclass
class Config:
    repo: str
    sdk: str
    out: str
    api: int = 30
    gpu_mode: str = "software"
    allow_software: bool = True
    boot_timeout: int = 1200
    test_timeout: int = 1200
    build_timeout: int = 1800
    memory_mb: int = 3072
    port: int = 5556
    test_classes: str = DEFAULT_TESTS
    unit_tests: bool = True
    tools_tests: bool = True


class AndroidRunner:
    def __init__(self, config):
        self.config = config
        self.repo, self.sdk, self.out = map(Path, (config.repo, config.sdk, config.out))
        self.out.mkdir(parents=True, exist_ok=True)
        self.serial = f"emulator-{config.port}"
        self.adb = str(self.sdk / "platform-tools/adb")
        self.emulator = str(self.sdk / "emulator/emulator")
        self.avd_name = "mw_colab_" + re.sub(r"[^A-Za-z0-9_]", "_", self.out.name)
        self.env = dict(os.environ, ANDROID_HOME=str(self.sdk), ANDROID_SDK_ROOT=str(self.sdk),
                        ANDROID_AVD_HOME=str(self.out / "avd"))
        self.env["PATH"] = os.pathsep.join((str(self.sdk / "platform-tools"),
                                             str(self.sdk / "emulator"), self.env["PATH"]))
        self.process = None
        self.accelerated = False
        self.rounds = []

    def adb_command(self, *args, log, timeout=60):
        return command([self.adb, "-s", self.serial, *args], log, env=self.env, timeout=timeout)

    def preflight(self):
        report = {"machine": platform.machine(), "system": platform.system(),
                  "cpu_count": os.cpu_count(), "kvm_exists": Path("/dev/kvm").exists(),
                  "kvm_read_write": os.access("/dev/kvm", os.R_OK | os.W_OK),
                  "gpu_mode": self.config.gpu_mode, "api": self.config.api}
        for name, args in (
            ("cpu", ["lscpu"]),
            ("gpu", ["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"]),
            ("accel", [self.emulator, "-accel-check"]),
            ("emulator_version", [self.emulator, "-version"]),
        ):
            log = self.out / "environment" / (name + ".log")
            result = command(args, log, env=self.env, timeout=30)
            report[name] = {**result, "output": log.read_text(encoding="utf-8", errors="replace")}
        self.accelerated = (report["system"] == "Linux" and report["kvm_read_write"]
                            and report["accel"]["returncode"] == 0)
        report["vm_acceleration"] = "KVM" if self.accelerated else "software"
        report["performance_note"] = (
            "Emulator measurements describe this VM only; compare on the same image and renderer."
            if self.accelerated else
            "No KVM. Slow software emulation; do not interpret frame timings as phone performance."
        )
        write_json(self.out / "environment.json", report)
        print(json.dumps({k: report[k] for k in ("cpu_count", "kvm_read_write", "vm_acceleration", "performance_note")}, indent=2))
        return report

    def start(self):
        if self.process and self.process.poll() is None:
            return
        report = self.preflight()
        if report["system"] != "Linux" or report["machine"] not in ("x86_64", "AMD64"):
            raise RuntimeError("This notebook requires an x86_64 Linux Colab runtime.")
        if not self.accelerated and not self.config.allow_software:
            raise RuntimeError("KVM unavailable. Enable ALLOW_SOFTWARE_EMULATION to try slow emulation.")
        for port in (self.config.port, self.config.port + 1):
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", port))
        Path(self.env["ANDROID_AVD_HOME"]).mkdir(parents=True, exist_ok=True)
        result = command([
            str(self.sdk / "cmdline-tools/latest/bin/avdmanager"), "create", "avd", "--force",
            "--name", self.avd_name, "--package", f"system-images;android-{self.config.api};google_apis;x86_64",
        ], self.out / "environment/avd-create.log", env=self.env, timeout=120, stdin=b"no\n")
        if result["status"] != "OK":
            raise RuntimeError("AVD creation failed; see environment/avd-create.log")
        args = [self.emulator, "-avd", self.avd_name, "-port", str(self.config.port),
                "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim",
                "-camera-back", "none", "-camera-front", "none", "-gpu", self.config.gpu_mode,
                "-accel", "on" if self.accelerated else "off",
                "-cores", str(min(os.cpu_count() or 2, 4) if self.accelerated else 1),
                "-memory", str(self.config.memory_mb), "-feature", "-Vulkan"]
        write_json(self.out / "environment/emulator-command.json", args)
        with (self.out / "environment/emulator.log").open("wb") as log:
            self.process = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                                            env=self.env, start_new_session=True)
        try:
            started = time.monotonic()
            next_update = 0
            while time.monotonic() - started < self.config.boot_timeout:
                if self.process.poll() is not None:
                    raise RuntimeError("Emulator exited before boot; see environment/emulator.log")
                boot = self.out / "environment/boot-property.log"
                state = self.adb_command("shell", "getprop", "sys.boot_completed", log=boot, timeout=15)
                if state["status"] == "OK" and boot.read_text().strip() == "1":
                    pm = self.out / "environment/package-manager.log"
                    self.adb_command("shell", "pm", "path", "android", log=pm, timeout=20)
                    if "package:" in pm.read_text():
                        break
                if time.monotonic() - started >= next_update:
                    print(f"Emulator boot: {int(time.monotonic() - started)}s / {self.config.boot_timeout}s", flush=True)
                    next_update += 30
                time.sleep(5)
            else:
                raise TimeoutError(f"Emulator boot exceeded {self.config.boot_timeout}s (KVM={self.accelerated}).")
            for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                self.adb_command("shell", "settings", "put", "global", setting, "0",
                                 log=self.out / "environment" / (setting + ".log"))
            self.adb_command("shell", "input", "keyevent", "82", log=self.out / "environment/unlock.log")
            self.adb_command("shell", "wm", "size", "720x1280", log=self.out / "environment/size.log")
            self.adb_command("shell", "wm", "density", "320", log=self.out / "environment/density.log")
            self.adb_command("shell", "dumpsys", "SurfaceFlinger", log=self.out / "environment/graphics.log")
            report["boot_seconds"] = round(time.monotonic() - started, 2)
            write_json(self.out / "environment.json", report)
            print(f"Emulator ready: {self.serial}, {report['boot_seconds']}s, KVM={self.accelerated}")
        except BaseException:
            self.stop()
            raise

    def stop(self):
        if self.process and self.process.poll() is None:
            self.adb_command("emu", "kill", log=self.out / "environment/stop.log", timeout=15)
            stop_process(self.process)
        self.process = None

    def collect(self, destination):
        destination = Path(destination)
        destination.mkdir(parents=True, exist_ok=True)
        for name, args in (
            ("logcat", ("logcat", "-d", "-v", "threadtime")),
            ("gfxinfo", ("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")),
            ("meminfo", ("shell", "dumpsys", "meminfo", PACKAGE)),
            ("activity", ("shell", "dumpsys", "activity", "activities")),
        ):
            self.adb_command(*args, log=destination / (name + ".txt"), timeout=30)
        # Keep stderr out of the PNG stream.
        with (destination / "screen.png").open("wb") as png:
            try:
                subprocess.run([self.adb, "-s", self.serial, "exec-out", "screencap", "-p"],
                               stdout=png, stderr=subprocess.DEVNULL, env=self.env, timeout=30)
            except (OSError, subprocess.TimeoutExpired):
                pass
        self.adb_command("pull", f"/sdcard/Android/data/{PACKAGE}/files/", str(destination / "app-files"),
                         log=destination / "pull.log", timeout=180)

    def run_round(self, name):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
            raise ValueError("Invalid round name")
        root = self.out / "rounds" / name
        root.mkdir(parents=True, exist_ok=False)
        result = {"name": name, "status": "ERROR", "test_classes": self.config.test_classes,
                  "stages": {}, "vm_acceleration": "KVM" if self.accelerated else "software"}
        stages = result["stages"]
        try:
            (self.repo / "local.properties").write_text(f"sdk.dir={self.sdk}\n", encoding="utf-8")
            (self.repo / "gradlew").chmod(0o755)
            gradle = [str(self.repo / "gradlew"), "--console=plain", "--build-cache",
                      f"--max-workers={min(os.cpu_count() or 2, 4)}"]
            print(f"[{name}] Build app and instrumentation APKs", flush=True)
            stages["build"] = command(gradle + [":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                                       root / "build.log", cwd=self.repo, env=self.env,
                                       timeout=self.config.build_timeout)
            if stages["build"]["status"] != "OK":
                result["status"] = "BUILD_FAILED"
                return result
            artifacts = root / "artifacts"
            artifacts.mkdir()
            for rel in ("app/build/outputs/apk/debug/app-debug.apk",
                        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
                source = self.repo / rel
                shutil.copy2(source, artifacts / source.name)
            result["apk_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts.iterdir()}
            if self.config.unit_tests:
                print(f"[{name}] Unit tests", flush=True)
                stages["unit_tests"] = command(gradle + ["test"], root / "unit-tests.log",
                                                cwd=self.repo, env=self.env, timeout=self.config.build_timeout)
            if self.config.tools_tests:
                print(f"[{name}] Host tool tests", flush=True)
                stages["tools_tests"] = command(["python", "-m", "pytest", "tools/", "-q"],
                                                 root / "tools-tests.log", cwd=self.repo, env=self.env,
                                                 timeout=self.config.test_timeout)
            for apk in ("app-debug.apk", "app-debug-androidTest.apk"):
                install = self.adb_command("install", "-r", "-t", str(artifacts / apk),
                                           log=root / (apk + ".install.log"), timeout=180)
                stages["install_" + apk] = install
                if install["status"] != "OK":
                    result["status"] = "INSTALL_FAILED"
                    return result
            # Each round starts from the same empty app data and log buffers.
            cleared = self.adb_command("shell", "pm", "clear", PACKAGE, log=root / "clear-data.log")
            if cleared["status"] != "OK" or "Success" not in (root / "clear-data.log").read_text():
                result["status"] = "DEVICE_SETUP_FAILED"
                return result
            self.adb_command("logcat", "-c", log=root / "clear-logcat.log")
            print(f"[{name}] Device tests: {self.config.test_classes}", flush=True)
            invocation = self.adb_command("shell", "am", "instrument", "-w", "-r", "-e", "class",
                                          self.config.test_classes, INSTRUMENTATION,
                                          log=root / "instrumentation.log", timeout=self.config.test_timeout)
            stages["instrumentation"] = instrumentation_result(
                (root / "instrumentation.log").read_text(encoding="utf-8", errors="replace"),
                invocation["returncode"], invocation["status"] == "TIMEOUT")
            if invocation["status"] == "TIMEOUT":
                self.adb_command("shell", "am", "force-stop", PACKAGE, log=root / "force-stop.log")
            result["status"] = "PASSED" if (stages["instrumentation"]["status"] == "PASSED"
                and all(v["status"] == "OK" for k, v in stages.items() if k != "instrumentation")) else "TEST_FAILED"
        except Exception as error:
            result["error"] = str(error)
        finally:
            try:
                self.collect(root / "device")
            except Exception as error:
                result["collection_error"] = str(error)
            write_json(root / "result.json", result)
            self.rounds.append(result)
            print(f"[{name}] {result['status']}", flush=True)
        return result


def apply_source_patch(repo, patch, output):
    """Apply a model proposal only to existing production sources; return backups.

    Test definitions and the runner stay fixed so a candidate cannot pass by
    weakening its own verification. Restoring backups preserves the input tree.
    """
    repo, patch, output = map(Path, (repo, patch, output))
    listed = subprocess.run(["git", "-C", str(repo), "apply", "--numstat", "-z", str(patch)],
                            capture_output=True, check=True)
    paths = []
    for entry in listed.stdout.decode("utf-8").split("\0"):
        if not entry:
            continue
        fields = entry.split("\t", 2)
        if len(fields) != 3 or not fields[0].isdigit() or not fields[1].isdigit():
            raise ValueError("Only text patches are supported")
        rel = fields[2]
        target = (repo / rel).resolve()
        if (not target.is_relative_to(repo.resolve()) or "/src/main/" not in rel
                or target.suffix not in (".kt", ".java", ".cpp", ".h", ".hpp")
                or not target.is_file() or target.is_symlink()):
            raise ValueError(f"Patch must change existing production source only: {rel}")
        paths.append(rel)
    if not paths:
        raise ValueError("Empty patch")
    checked = command(["git", "apply", "--check", str(patch)], output / "patch-check.log", cwd=repo)
    if checked["status"] != "OK":
        raise ValueError("Patch does not apply to the tested source; see patch-check.log")
    backups = {rel: (repo / rel).read_bytes() for rel in paths}
    applied = command(["git", "apply", str(patch)], output / "patch-apply.log", cwd=repo)
    if applied["status"] != "OK":
        restore_sources(repo, backups)
        raise ValueError("Patch application failed")
    return backups


def restore_sources(repo, backups):
    for rel, data in backups.items():
        (Path(repo) / rel).write_bytes(data)


def export_results(out, destination):
    out, destination = Path(out), Path(destination)
    archive = destination / "results.tar.gz"
    artifacts_archive = destination / "artifacts.tar.gz"
    destination.mkdir(parents=True, exist_ok=True)
    # The AVD contains GBs of disposable disk images; never put it in reports.
    files = [p for p in out.rglob("*") if p.is_file() and "avd" not in p.relative_to(out).parts]
    write_json(out / "run-manifest.json", {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                                           "files": [str(p.relative_to(out)) for p in files]})
    files.append(out / "run-manifest.json")
    with tarfile.open(archive, "w:gz") as results, tarfile.open(artifacts_archive, "w:gz") as apks:
        for path in files:
            target = apks if path.suffix == ".apk" else results
            target.add(path, arcname=str(path.relative_to(out)))
    return archive, artifacts_archive
