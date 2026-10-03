from __future__ import annotations

ALLOWED_OPS = {
    "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
    "trust.rotate", "trust.revoke",
    "bridge.status",
    "session.list", "session.start", "session.status", "session.pause", "session.continue",
    "session.handoff", "session.stop", "session.snapshot",
    "observe.semantic", "observe.page_debug", "ui.search", "ui.element",
    "app_graph.get", "brain.recall", "action.execute", "teach.start", "teach.status",
    "teach.stop", "debug.snapshot", "pair.begin", "pair.complete", "pair.qr.complete", "pair.revoke",
    "manual.execute", "clipboard.get", "clipboard.set",
    "skill.compile", "skill.run", "skill.match",
    "atlas.places", "atlas.get", "atlas.diff",
    "mapping.start", "mapping.pause", "mapping.stop", "mapping.status",
    "secrets.slots", "secrets.request",
    "ask.start", "ask.status", "ask.cancel",
    "apps.list",
    "runs.list", "runs.get", "runs.mark",
    "atlas.versions", "scenarios.list", "knowledge.get", "atlas.here",
    # Cyclone Lab: measured Mind missions (start, watch, answer as the owner, read back).
    "lab.start", "lab.status", "lab.answer", "lab.record",
    "cc.start", "cc.status", "cc.answer", "cc.key", "cc.media",
    # Cyclone Ports (plan 48 run 4): the PC's Port Hub polls the phone's outbox and answers its runs' waits.
    "ports.poll", "ports.blob", "ports.answer", "ports.file",
    # Plan 43 T6: the sign-up maps the phone learned (schemas only), and forgetting one.
    "signup.maps", "signup.forget",
    # Plan 49 (alpha.102): the phone's own numbers for the Numbers page (numbers only, never a text or a code).
    "numbers.list",
    # Plan 43 T4: the phone's profiles, switching between them, and each Cyclone profile's apps.
    "profiles.list", "profiles.apps", "profiles.switch", "profiles.app",
    # Alpha 87: why Cyclone stopped last time, and the freezes it caught (read only).
    "health.report",
    # Cyclone Marketplace: the phone's store of recipes and connections.
    "market.catalog", "market.install", "market.remove", "market.run",
    # Learn: one press per run turns what it saw and did into app knowledge on the phone.
    "learn.run",
    # Grounded skills: the owner's saved skills and where each lives on the map.
    "skills.list",
    # The app dictionary (plan 36 §7) and the phone's models for the mapping picker.
    "dictionary.get", "dictionary.edit", "models.list",
    # The App Manual (plan 36 §8): abilities, the self-quiz and the manual as text. Read only.
    "manual.get",
}
UNAUTHENTICATED_OPS = {
    "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
    "pair.begin", "pair.complete", "pair.qr.complete",
}
