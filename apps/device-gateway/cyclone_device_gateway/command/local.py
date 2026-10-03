"""Local MCP servers on the PC (plan 34 M2). The owner pastes a server's config (the usual `mcpServers` JSON), sees
exactly what will run, and approves that exact, pinned command once. Only then does the gateway start it, and only
through its MCP tools, under the same allowlist, caps and approvals as any connection.

What this module refuses:
- a launcher that is not `npx`, `uvx`, `docker`, `node` or `python` (a bare name, found on PATH);
- anything a shell would read (the command is an argument list, never a shell line; `& | < > ^ % "` and new lines are
  refused because Windows runs `npx.cmd` through cmd.exe);
- an unpinned package (`pkg@1.2.3`, `pkg==1.2.3`, a Docker tag or digest), or a script that changed since approval;
- env names that change how programs load (`PATH`, `NODE_OPTIONS`, `PYTHONPATH`, `LD_PRELOAD`…);
- a Docker run with host-level powers (`--privileged`, `--cap-add`, `--device`, `--pid`…).

What it does: the program gets its own working folder, a small environment (never the gateway's token), a Windows
Job Object so it ends with the runtime, a restart limit (3 in 10 minutes), an idle stop (15 minutes) and a short,
redacted log of what it wrote to stderr.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Callable

from ..desktop_runtime.v5_contract import INLINE_SECRET
from .mcp import McpError, Session, _pick

LAUNCHERS = ("npx", "uvx", "docker", "node", "python", "python3", "py")
SHELL_CHARS = re.compile(r'[&|<>^%"`\r\n\x00]')
NPM_PINNED = re.compile(r"^(@[a-z0-9][\w.-]*/)?[a-z0-9][\w.-]*@\d+\.\d+\.\d+(?:[-+][\w.-]+)?$", re.I)
PY_PINNED = re.compile(r"^[A-Za-z0-9][\w.-]*(\[[\w,.-]+\])?(==|@)\d+(\.\d+)*[\w.+-]*$")
DOCKER_IMAGE = re.compile(r"^[a-z0-9][\w./-]*(@sha256:[0-9a-f]{64}|:[\w][\w.-]{0,127})$")
ENV_NAME = re.compile(r"^[A-Z_][A-Z0-9_]{0,63}$")
FORBIDDEN_ENV = {"PATH", "PATHEXT", "COMSPEC", "NODE_OPTIONS", "NODE_PATH", "PYTHONPATH", "PYTHONHOME", "PYTHONSTARTUP",
                 "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "SYSTEMROOT", "WINDIR",
                 "NPM_CONFIG_PREFIX", "UV_INDEX_URL", "PIP_INDEX_URL", "DOCKER_HOST"}
DOCKER_REFUSED = {"--privileged", "--cap-add", "--device", "--pid", "--ipc", "--userns", "--security-opt", "--entrypoint",
                  "--uts", "--cgroupns", "--add-host"}
DOCKER_VALUE_FLAGS = {"-e", "--env", "-v", "--volume", "--mount", "--name", "-w", "--workdir", "--network", "--user", "-u",
                      "--memory", "-m", "--cpus", "--platform", "-p", "--publish"}
DOCKER_FLAGS = {"-i", "--interactive", "--rm", "--init", "--read-only", "-t", "--tty"}
PASS_ENV = ("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA",
            "PROGRAMFILES", "PROGRAMFILES(X86)", "COMSPEC", "PATHEXT", "HOMEDRIVE", "HOMEPATH", "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE", "OS", "LANG", "PATH", "USERNAME")
RESTARTS = 3
RESTART_WINDOW_S = 10 * 60
IDLE_STOP_S = 15 * 60
LOG_LINES = 200


class LaunchError(ValueError):
    """A config Cyclone will not run; the message is for the owner."""


def parse_config(raw: Any) -> list[dict[str, Any]]:
    """`{"mcpServers": {name: {command, args, env}}}`, one `{command, args, env}`, or text holding either."""
    if isinstance(raw, str):
        try:
            raw = json.loads(raw)
        except ValueError as exc:
            raise LaunchError("That is not a server config. Paste the JSON from the server's README.") from exc
    if not isinstance(raw, dict):
        raise LaunchError("That is not a server config.")
    servers = raw.get("mcpServers", raw.get("servers"))
    if servers is None:
        servers = {"server": raw}
    if not isinstance(servers, dict) or not 1 <= len(servers) <= 5:
        raise LaunchError("Paste one to five servers at a time.")
    out = []
    for name, spec in servers.items():
        if not isinstance(spec, dict):
            raise LaunchError(f"{name}: not a server config.")
        if spec.get("url") or spec.get("type") in ("http", "sse", "streamable-http"):
            raise LaunchError(f"{name}: this one is a remote server. Add it by its address instead.")
        extra = set(spec) - {"command", "args", "env", "type", "disabled", "description"}
        if extra:
            raise LaunchError(f"{name}: Cyclone does not use {sorted(extra)[0]}; remove it.")
        out.append(check_launch(str(name), spec.get("command"), spec.get("args", []), spec.get("env") or {}))
    return out


def _base(command: str) -> str:
    base = command.strip().replace("\\", "/").rsplit("/", 1)[-1].lower()
    for suffix in (".exe", ".cmd", ".bat"):
        if base.endswith(suffix):
            base = base[: -len(suffix)]
    return base


def _file_hash(path: str) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def check_launch(name: str, command: Any, args: Any, env: Any) -> dict[str, Any]:
    """A checked launch: the exact argument list, what is pinned, env names (values stay out) and a hash."""
    label = re.sub(r"[^\w .-]", "", name)[:60] or "server"
    if not isinstance(command, str) or not command.strip():
        raise LaunchError(f"{label}: the config has no command.")
    if "/" in command or "\\" in command or ":" in command:
        raise LaunchError(f"{label}: use the program's name ({_base(command)}), not a path to it.")
    base = _base(command)
    if base not in LAUNCHERS:
        raise LaunchError(f"{label}: Cyclone runs servers with npx, uvx, docker, node or python, not {base}.")
    if not isinstance(args, list) or len(args) > 40 or not all(isinstance(a, str) and len(a) <= 500 for a in args):
        raise LaunchError(f"{label}: args must be a list of words.")
    if any(SHELL_CHARS.search(a) for a in args):
        raise LaunchError(f'{label}: Cyclone does not pass & | < > ^ % " or new lines to a program.')
    if not isinstance(env, dict) or len(env) > 30:
        raise LaunchError(f"{label}: env must be names and values.")
    for key, value in env.items():
        if not isinstance(key, str) or not ENV_NAME.match(key) or key.upper() in FORBIDDEN_ENV or key.upper().startswith(("DYLD_", "LD_")):
            raise LaunchError(f"{label}: Cyclone does not set the env name {str(key)[:40]}.")
        if not isinstance(value, str) or len(value) > 4000:
            raise LaunchError(f"{label}: env values are text.")
    launcher = "python" if base in ("python3", "py") else base
    pinned, file_hash = _pinned(label, launcher, args)
    body = {"launcher": launcher, "args": args, "env": sorted(env), "file": file_hash}
    digest = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
    return {
        "name": label, "launcher": launcher, "args": list(args), "envKeys": sorted(env), "env": dict(env), "pinned": pinned,
        "fileHash": file_hash, "display": " ".join([launcher, *args]), "hash": digest,
    }


def _pinned(label: str, launcher: str, args: list[str]) -> tuple[str, str | None]:
    if launcher == "npx":
        words = [a for a in args if a not in ("-y", "--yes", "--quiet", "-q")]
        if not words or words[0].startswith("-"):
            raise LaunchError(f"{label}: npx needs the package first, like @org/server@1.2.3.")
        if not NPM_PINNED.match(words[0]):
            raise LaunchError(f"{label}: pin the version, like {words[0].split('@')[0] or words[0]}@1.2.3, so Cyclone runs one exact version.")
        return words[0], None
    if launcher == "uvx":
        words = [a for a in args if a not in ("-q", "--quiet")]
        if not words or words[0].startswith("-"):
            raise LaunchError(f"{label}: uvx needs the package first, like server==1.2.3.")
        if not PY_PINNED.match(words[0]):
            raise LaunchError(f"{label}: pin the version, like {re.split('[=@]', words[0])[0]}==1.2.3.")
        return words[0], None
    if launcher == "docker":
        if not args or args[0] != "run":
            raise LaunchError(f"{label}: Cyclone runs Docker servers with docker run.")
        i = 1
        while i < len(args):
            word = args[i]
            flag = word.split("=", 1)[0]
            if flag in DOCKER_REFUSED or (flag == "--network" and ("host" in word or (i + 1 < len(args) and args[i + 1] == "host"))):
                raise LaunchError(f"{label}: Cyclone does not run containers with {flag}.")
            if flag in DOCKER_VALUE_FLAGS:
                i += 1 if "=" in word else 2
                continue
            if flag in DOCKER_FLAGS or re.fullmatch(r"-[it]+", word):
                i += 1
                continue
            if word.startswith("-"):
                raise LaunchError(f"{label}: Cyclone does not know the Docker option {flag}.")
            if not DOCKER_IMAGE.match(word) or word.endswith(":latest"):
                raise LaunchError(f"{label}: name the image with a version tag or digest (not latest).")
            return word, None
        raise LaunchError(f"{label}: the docker run has no image.")
    # node or python: a script file on this PC, pinned by its SHA-256.
    if not args or args[0].startswith("-"):
        raise LaunchError(f"{label}: {launcher} needs the script's full path first.")
    script = Path(args[0])
    if not script.is_absolute() or not script.is_file():
        raise LaunchError(f"{label}: {args[0][:80]} is not a file on this PC (use its full path).")
    return str(script), _file_hash(str(script))


class StdioClient(Session):
    """MCP over a program's stdin and stdout: one JSON-RPC message per line."""

    def __init__(self, process: subprocess.Popen, log: Callable[[str], None]) -> None:
        self._process = process
        self._log = log
        self._next, self._ready, self.server = 0, False, {}
        self._answers: dict[Any, dict[str, Any]] = {}
        self._cond = threading.Condition()
        self._write = threading.Lock()
        self._closed = False
        self.last_used = time.monotonic()
        threading.Thread(target=self._read, name="cyclone-mcp-stdio", daemon=True).start()
        threading.Thread(target=self._read_errors, name="cyclone-mcp-stderr", daemon=True).start()

    @property
    def alive(self) -> bool:
        return not self._closed and self._process.poll() is None

    def _send_line(self, message: dict[str, Any]) -> None:
        data = (json.dumps(message, separators=(",", ":")) + "\n").encode()
        with self._write:
            assert self._process.stdin is not None
            self._process.stdin.write(data)
            self._process.stdin.flush()

    def _read(self) -> None:
        assert self._process.stdout is not None
        try:
            for raw in iter(lambda: self._process.stdout.readline(8 * 1024 * 1024), b""):
                try:
                    message = json.loads(raw)
                except ValueError:
                    self._log("(stdout) " + raw.decode("utf-8", "replace")[:300])
                    continue
                if not isinstance(message, dict):
                    continue
                if "method" in message and "id" in message:
                    # The server asks Cyclone for something (sampling, roots…): Cyclone offers nothing back.
                    try:
                        self._send_line({"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Cyclone does not offer this."}})
                    except (OSError, ValueError):
                        pass
                    continue
                if "id" in message and ("result" in message or "error" in message):
                    with self._cond:
                        self._answers[message["id"]] = message
                        self._cond.notify_all()
        except (OSError, ValueError):
            pass
        finally:
            with self._cond:
                self._closed = True
                self._cond.notify_all()

    def _read_errors(self) -> None:
        assert self._process.stderr is not None
        try:
            for raw in iter(lambda: self._process.stderr.readline(64 * 1024), b""):
                self._log(raw.decode("utf-8", "replace").rstrip()[:300])
        except (OSError, ValueError):
            pass

    def _post(self, message: dict[str, Any], *, timeout: float) -> dict[str, Any] | None:
        if not self.alive:
            raise McpError("The local server stopped. Look at its log in Connections.")
        self.last_used = time.monotonic()
        try:
            self._send_line(message)
        except (OSError, ValueError) as exc:
            raise McpError("The local server stopped. Look at its log in Connections.") from exc
        wanted = message.get("id")
        if wanted is None:
            return None
        with self._cond:
            self._cond.wait_for(lambda: wanted in self._answers or self._closed, timeout=timeout)
            answer = self._answers.pop(wanted, None)
        self.last_used = time.monotonic()
        if answer is None:
            raise McpError("The local server did not answer in time." if not self._closed else "The local server stopped.")
        return _pick([answer], wanted)

    def close(self) -> None:
        with self._cond:
            self._closed = True
            self._cond.notify_all()
        for stream in (self._process.stdin, self._process.stdout, self._process.stderr):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass
        if self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()


class _Job:
    """A Windows Job Object that kills the program (and anything it starts) when the runtime ends."""

    def __init__(self) -> None:
        self.handle = None
        if os.name != "nt":
            return
        import ctypes
        from ctypes import wintypes

        class IoCounters(ctypes.Structure):
            _fields_ = [(n, ctypes.c_ulonglong) for n in ("r", "w", "o", "rb", "wb", "ob")]

        class Basic(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD)]

        class Extended(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", Basic), ("IoInfo", IoCounters), ("ProcessMemoryLimit", ctypes.c_size_t),
                        ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                        ("PeakJobMemoryUsed", ctypes.c_size_t)]

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.CreateJobObjectW.restype = wintypes.HANDLE
        handle = kernel32.CreateJobObjectW(None, None)
        if not handle:
            return
        info = Extended()
        info.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if kernel32.SetInformationJobObject(handle, 9, ctypes.byref(info), ctypes.sizeof(info)):  # ExtendedLimitInformation
            self.handle = handle
            self._kernel32 = kernel32

    def add(self, process: subprocess.Popen) -> None:
        if self.handle is not None:
            self._kernel32.AssignProcessToJobObject(self.handle, int(process._handle))  # type: ignore[attr-defined]


class LocalServers:
    """Starts and keeps approved local servers. One program per connection, started when first used."""

    def __init__(self, root: Path, *, secrets: Callable[[str], dict[str, str]], which: Callable[[str], str | None] = shutil.which,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self.root = root
        self._secrets = secrets
        self._which = which
        self._clock = clock
        self._lock = threading.Lock()
        self._clients: dict[str, StdioClient] = {}
        self._starts: dict[str, deque[float]] = {}
        self._logs: dict[str, deque[str]] = {}
        self._job = _Job()

    def logs(self, connection_id: str) -> list[str]:
        return list(self._logs.get(connection_id, ()))

    def running(self, connection_id: str) -> bool:
        client = self._clients.get(connection_id)
        return bool(client and client.alive)

    def session(self, connection_id: str, launch: dict[str, Any]) -> StdioClient:
        with self._lock:
            client = self._clients.get(connection_id)
            if client and client.alive:
                return client
            if client:
                client.close()
            starts = self._starts.setdefault(connection_id, deque(maxlen=RESTARTS))
            now = self._clock()
            if len(starts) == RESTARTS and now - starts[0] < RESTART_WINDOW_S:
                raise McpError("It stopped 3 times in 10 minutes. Look at its log, then press Check again.")
            client = self._start(connection_id, launch)
            starts.append(now)
            self._clients[connection_id] = client
            return client

    def _start(self, connection_id: str, launch: dict[str, Any]) -> StdioClient:
        if launch.get("fileHash") and (not Path(launch["args"][0]).is_file() or _file_hash(launch["args"][0]) != launch["fileHash"]):
            raise McpError("The script changed since you approved it. Approve it again.")
        executable = self._which(launch["launcher"]) or (self._which("python3") if launch["launcher"] == "python" else None)
        if not executable:
            raise McpError(f"{launch['launcher']} is not installed on this PC.")
        folder = self.root / connection_id
        folder.mkdir(parents=True, exist_ok=True)
        secret_env = self._secrets(connection_id)
        env = {k: os.environ[k] for k in PASS_ENV if k in os.environ}
        env.update({k: v for k, v in secret_env.items() if k in launch["envKeys"]})
        hidden = [v for v in secret_env.values() if len(v) >= 4]
        log = self._logs.setdefault(connection_id, deque(maxlen=LOG_LINES))

        def write(line: str) -> None:
            for value in hidden:
                line = line.replace(value, "[hidden]")
            log.append(INLINE_SECRET.sub("[hidden]:", line))

        try:
            process = subprocess.Popen([executable, *launch["args"]], cwd=str(folder), env=env, shell=False,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except OSError as exc:
            raise McpError(f"The local server could not start ({exc.__class__.__name__}).") from exc
        try:
            self._job.add(process)
        except OSError:
            pass
        write(f"started {launch['display'][:200]}")
        return StdioClient(process, write)

    def stop(self, connection_id: str) -> None:
        with self._lock:
            client = self._clients.pop(connection_id, None)
        if client:
            client.close()

    def stop_all(self) -> None:
        for connection_id in list(self._clients):
            self.stop(connection_id)

    def sweep(self) -> None:
        """Stop programs nobody used for 15 minutes; they start again when needed."""
        now = time.monotonic()
        for connection_id, client in list(self._clients.items()):
            if now - client.last_used > IDLE_STOP_S:
                self.stop(connection_id)
