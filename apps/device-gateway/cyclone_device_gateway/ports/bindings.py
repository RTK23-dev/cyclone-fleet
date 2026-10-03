"""Port bindings (plan 48, run 2): which plugin serves each port, everywhere or for one routine or one app.

Rules:
- **Automatic** (no choice made):
  - an out port goes to every live plugin that serves it and that the owner allowed for it (fan-out);
  - an in port needs exactly one plugin to answer. With one live candidate it is that plugin; with two or more it is
    a **conflict** until the owner chooses, and nothing answers meanwhile. A wait never goes to whichever plugin is
    fastest.
- **A choice** names the plugins:
  - out ports take any number, and none means the port is off for that scope;
  - in ports take at most one.
- **Scopes**, most specific first: ``routine:<id>`` → ``app:<package>`` → ``default``. The first scope with a choice
  wins, otherwise automatic.
- A chosen plugin that isn't live (paused, failing, removed, port switched off) serves nothing. The port shows as
  **unavailable** instead of quietly falling back to another plugin.
"""
from __future__ import annotations

import re
from typing import Any

from . import kit

SCOPE = re.compile(r"^(default|routine:[a-z]{1,4}_[A-Za-z0-9_-]{6,40}|app:[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)$")
STATES = ("ok", "empty", "conflict", "off", "unavailable", "vault")


class BindingError(ValueError):
    """A refused binding; the message is for the owner."""


def check_scope(scope: Any) -> str:
    if not isinstance(scope, str) or not SCOPE.match(scope):
        raise BindingError("Choose everywhere, a routine, or an app (like com.instagram.android).")
    return scope


def chain(routine: str | None = None, app: str | None = None) -> list[str]:
    scopes = []
    if routine:
        scopes.append(check_scope(f"routine:{routine}"))
    if app:
        scopes.append(check_scope(f"app:{app}"))
    return scopes + ["default"]


def catalog_ports(plugins: list[dict[str, Any]]) -> list[tuple[str, str, str, bool]]:
    """(port, way, sensitivity, plugin_served) for the catalog plus every extension port a plugin serves."""
    rows = [(p.name, p.way, p.sensitivity, p.plugin_served) for p in kit.CATALOG.values()]
    seen = {r[0] for r in rows}
    for plugin in plugins:
        for s in plugin["serves"]:
            if s["extension"] and s["port"] not in seen:
                rows.append((s["port"], s["way"], s["sensitivity"], True))
                seen.add(s["port"])
    return rows


def candidates(plugins: list[dict[str, Any]], port: str) -> list[dict[str, Any]]:
    """Plugins that serve this port and are allowed it, live or not (a choice may name a plugin that is down now)."""
    out = []
    for p in plugins:
        if any(s["port"] == port and s["allowed"] for s in p["serves"]):
            out.append({"name": p["name"], "title": p["title"], "live": p["status"] == "active", "status": p["status"]})
    return out


def resolve_port(port: str, way: str, plugin_served: bool, cands: list[dict[str, Any]],
                 choices: dict[str, list[str]], scopes: list[str]) -> dict[str, Any]:
    """The plugins this port reaches for the given scope chain, with a state and where the choice came from."""
    if not plugin_served:
        return {"state": "vault", "effective": [], "chosen": None, "source": "hub"}
    live = [c["name"] for c in cands if c["live"]]
    for scope in scopes:
        if scope in choices:
            chosen = choices[scope]
            if not chosen:
                return {"state": "off", "effective": [], "chosen": [], "source": scope}
            effective = [n for n in chosen if n in live]
            state = "ok" if len(effective) == len(chosen) else "unavailable"
            return {"state": state, "effective": effective, "chosen": chosen, "source": scope}
    if way == "out":
        return {"state": "ok" if live else "empty", "effective": live, "chosen": None, "source": "automatic"}
    if len(live) == 1:
        return {"state": "ok", "effective": live, "chosen": None, "source": "automatic"}
    if not live:
        return {"state": "empty", "effective": [], "chosen": None, "source": "automatic"}
    return {"state": "conflict", "effective": [], "chosen": None, "source": "automatic"}


def table(plugins: list[dict[str, Any]], all_choices: dict[tuple[str, str], list[str]], scopes: list[str]) -> list[dict[str, Any]]:
    """Every port, resolved for the scope chain. ``override`` says whether the first scope itself made a choice."""
    rows = []
    for port, way, sensitivity, served in catalog_ports(plugins):
        cands = candidates(plugins, port)
        choices = {scope: names for (scope, p), names in all_choices.items() if p == port}
        resolved = resolve_port(port, way, served, cands, choices, scopes)
        inherited = {k: v for k, v in choices.items() if k in scopes[1:]}
        below = resolve_port(port, way, served, cands, inherited, scopes[1:]) if len(scopes) > 1 else None
        rows.append({
            "port": port, "way": way, "sensitivity": sensitivity, "pluginServed": served,
            "extension": kit.is_extension(port), "candidates": cands, **resolved,
            "override": scopes[0] in choices,
            "inherits": None if below is None else {"state": below["state"], "effective": below["effective"],
                                                     "source": below["source"]},
        })
    return rows


def validate_choice(port: str, way: str | None, plugins: Any, cands: list[dict[str, Any]]) -> list[str] | None:
    if plugins is None:
        return None
    if not isinstance(plugins, list) or not all(isinstance(p, str) for p in plugins):
        raise BindingError("Send the plugins as a list of names, or null for automatic.")
    plugins = list(dict.fromkeys(plugins))
    names = {c["name"] for c in cands}
    unknown = [p for p in plugins if p not in names]
    if unknown:
        raise BindingError(f"{unknown[0]} doesn't serve {port}, or isn't allowed it.")
    if way == "in" and len(plugins) > 1:
        raise BindingError("A waiting run takes its answer from one plugin. Choose one.")
    return plugins
