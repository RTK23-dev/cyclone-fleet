"""The SSH tunnel a VMOS phone's ADB goes through, run and watched by Cyclone.

Windows' own `ssh.exe` (OpenSSH, part of Windows 10 and 11) carries the tunnel. VMOS's key is an SSH password, so it
reaches ssh through SSH_ASKPASS: ssh runs this PC's own runtime in "askpass" mode, which prints the key from its
environment and exits. The key is never on a command line, in a file or in a log. A key that is a private key instead
is written to a file only this user can read, and deleted when the tunnel stops.

The tunnel always binds 127.0.0.1 on a port Cyclone keeps per phone, so the phone keeps the same fleet identity across
renewals, and runs in the foreground (never `-f`) so Cyclone sees it die.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any, Callable

from ..adb.client import _hidden_process_kwargs
from .models import AdbLink

ASKPASS_MODE = "CYCLONE_ASKPASS_MODE"
ASKPASS_SECRET = "CYCLONE_ASKPASS_SECRET"
STDERR_KEEP = 2_000

# What ssh says, and what it means for the owner.
SSH_ERRORS = (
    ("permission denied", "TUNNEL_KEY_REFUSED", "VMOS refused the ADB key. Cyclone asks for a new one."),
    ("host key verification failed", "TUNNEL_HOST_CHANGED", "The VMOS tunnel server's identity changed."),
    ("connection refused", "TUNNEL_REFUSED", "The VMOS tunnel server refused the connection."),
    ("connection timed out", "TUNNEL_TIMEOUT", "The VMOS tunnel server didn't answer."),
    ("could not resolve hostname", "TUNNEL_DNS", "This PC couldn't find the VMOS tunnel server."),
    ("address already in use", "TUNNEL_PORT_BUSY", "Another program uses this phone's local port."),
    ("remote port forwarding failed", "TUNNEL_FORWARD", "VMOS didn't open the ADB forward."),
    ("cannot listen to port", "TUNNEL_PORT_BUSY", "Another program uses this phone's local port."),
)


def askpass_main() -> bool:
    """Called first thing by the runtime entry point. True when this process is only ssh's password helper."""
    if os.environ.get(ASKPASS_MODE) != "1":
        return False
    sys.stdout.write(os.environ.get(ASKPASS_SECRET, "") + "\n")
    sys.stdout.flush()
    return True


def find_ssh() -> str | None:
    found = shutil.which("ssh")
    if found:
        return found
    if os.name == "nt":
        system = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "OpenSSH" / "ssh.exe"
        if system.is_file():
            return str(system)
    return None


def askpass_program(scratch: Path) -> str:
    """The program ssh runs for the key: the packaged runtime itself, or a tiny script in development."""
    if getattr(sys, "frozen", False):
        return sys.executable
    scratch.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        path = scratch / "cyclone-askpass.cmd"
        path.write_text(f'@"{sys.executable}" -c "import os,sys;sys.stdout.write(os.environ.get(\'{ASKPASS_SECRET}\',\'\')+chr(10))"\r\n',
                        encoding="utf-8")
    else:
        path = scratch / "cyclone-askpass.sh"
        path.write_text(f'#!/bin/sh\nprintf \'%s\\n\' "${ASKPASS_SECRET}"\n', encoding="utf-8")
        path.chmod(0o700)
    return str(path)


def ssh_argv(ssh: str, link: AdbLink, local_port: int, known_hosts: Path, identity: Path | None = None) -> list[str]:
    argv = [
        ssh, "-N", "-T",
        "-o", "ExitOnForwardFailure=yes",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=3",
        "-o", "ConnectTimeout=15",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", f"UserKnownHostsFile={known_hosts}",
        "-o", "HostKeyAlgorithms=+ssh-rsa",
        "-o", "PubkeyAcceptedAlgorithms=+ssh-rsa",
        "-o", "NumberOfPasswordPrompts=1",
        "-o", "LogLevel=ERROR",
        "-p", str(link.ssh_port),
        "-L", f"127.0.0.1:{local_port}:{link.target_host}:{link.target_port}",
    ]
    if identity is not None:
        argv += ["-i", str(identity), "-o", "IdentitiesOnly=yes", "-o", "PreferredAuthentications=publickey"]
    else:
        argv += ["-o", "PreferredAuthentications=password,keyboard-interactive", "-o", "PubkeyAuthentication=no"]
    argv.append(f"{link.ssh_user}@{link.ssh_host}")
    return argv


def explain(stderr: str) -> tuple[str, str]:
    lower = stderr.lower()
    for needle, code, message in SSH_ERRORS:
        if needle in lower:
            return code, message
    return "TUNNEL_CLOSED", "The VMOS tunnel closed. Cyclone opens it again."


class SshTunnel:
    def __init__(self, link: AdbLink, local_port: int, *, ssh: str, scratch: Path,
                 spawn: Callable[..., Any] = subprocess.Popen):
        self.link = link
        self.local_port = local_port
        self.ssh = ssh
        self.scratch = scratch
        self.spawn = spawn
        self.process: Any = None
        self._stderr = ""
        self._identity: Path | None = None

    def start(self) -> None:
        secret = self.link.secret or ""
        env = dict(os.environ)
        env.pop(ASKPASS_SECRET, None)
        if secret.startswith("-----BEGIN"):
            self._identity = self.scratch / f"key-{self.local_port}"
            self.scratch.mkdir(parents=True, exist_ok=True)
            fd = os.open(self._identity, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(secret if secret.endswith("\n") else secret + "\n")
        else:
            env.update({"SSH_ASKPASS": askpass_program(self.scratch), "SSH_ASKPASS_REQUIRE": "force",
                        "DISPLAY": env.get("DISPLAY") or "cyclone:0", ASKPASS_MODE: "1", ASKPASS_SECRET: secret})
        argv = ssh_argv(self.ssh, self.link, self.local_port, self.scratch / "known_hosts", self._identity)
        self.process = self.spawn(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                  env=env, **_hidden_process_kwargs())
        stream = getattr(self.process, "stderr", None)
        if stream is not None:
            threading.Thread(target=self._read_stderr, args=(stream,), name="cyclone-cloud-tunnel", daemon=True).start()

    def _read_stderr(self, stream: Any) -> None:
        try:
            for raw in iter(stream.readline, b""):
                line = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)
                self._stderr = (self._stderr + line)[-STDERR_KEEP:]
        except Exception:
            pass

    def alive(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def failure(self) -> tuple[str, str]:
        return explain(self._stderr)

    def stop(self) -> None:
        process, self.process = self.process, None
        if process is not None and process.poll() is None:
            try:
                process.terminate()
                process.wait(timeout=5)
            except Exception:
                try:
                    process.kill()
                except Exception:
                    pass
        if self._identity is not None:
            try:
                self._identity.unlink()
            except OSError:
                pass
            self._identity = None
