"""Turn one sentence into one goal per phone - deterministically.

"unlock and check messages on Work Phone, open camera on Tablet"
        ->  Work Phone: "unlock and check messages"
            Tablet:     "open camera"

Why this is rule-based and not an LLM call: the person's OpenRouter key and
model live on each phone (Keystore-backed) and the PC must never hold them.
A PC-side model call would need that key. So the PC only does the part that
must be exact anyway - working out *which phone* each part of the sentence is
for - and each phone's own Mind does the thinking about *how*.

The rule is the same one the MCP layer enforces: never guess a device.
- A name that matches two phones is a question, not a coin flip.
- A sentence with no phone name, and more than one phone paired, is a question.
- Anything that isn't a clean parse is returned as needing confirmation so the
  person sees the split before anything runs on a phone.

This module is pure: no I/O, no clock, no network.
"""
from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Any

_APOSTROPHES = str.maketrans({"\u2019": "'", "\u2018": "'", "\u02bc": "'", "\u2032": "'"})

# Labels that are too generic to identify a phone. Matching them would turn any
# ordinary sentence ("open the phone app") into a device reference.
_GENERIC_LABELS = {"android phone", "android", "phone", "device", "unknown", "phones", "devices"}

_PREP = re.compile(r"\b(?:on|for|using|with|in|at|from|to|via|through)\s*$", re.IGNORECASE)
_VERB = re.compile(r"\b(?:tell|ask|have|get|make|let|instruct)\s*$", re.IGNORECASE)
_FILLER_ONLY = re.compile(r"^(?:(?:please|pls|hey|ok|okay|can you|could you|would you)[\s,]*)*$", re.IGNORECASE)

_TIER0 = re.compile(r"[.;!?\n]")
_TIER1 = re.compile(r",")
_TIER2 = re.compile(r"\b(?:and then|then|after that|afterwards|while|meanwhile|at the same time|also|plus)\b", re.IGNORECASE)
_TIER3 = re.compile(r"\band\b", re.IGNORECASE)
_SEQUENCING = re.compile(r"\b(?:and then|then|after that|afterwards)\b", re.IGNORECASE)

_LEAD_JUNK = re.compile(r"^[\s,;:.\-\u2013]+")
_LEAD_CONNECTOR = re.compile(
    r"^(?:(?:and then|and|then|also|while|meanwhile|plus|but|after that|afterwards|at the same time)\b[\s,]*)+", re.IGNORECASE)
_TRAIL_JUNK = re.compile(r"[\s,;:\-\u2013]+$")
_TRAIL_CONNECTOR = re.compile(r"(?:[\s,]*\b(?:and then|and|then|also|while|plus|but)\b)+[\s,.]*$", re.IGNORECASE)

_ALL_WORDS = r"(?:all|every|each|both)"
_BROADCAST = re.compile(
    rf"\b(?:(?:on|for|across|to|in)\s+)?{_ALL_WORDS}\s+(?:of\s+)?(?:(?:my|the|these|those|our)\s+)?"
    r"(?:(?:online|ready|paired|connected|android)\s+)*(?:phones?|devices?|androids?)\b",
    re.IGNORECASE,
)

MIN_GOAL = 3

# Text inside quotes is the *content* of an instruction ("send 'see you on Tablet day' to John"), so it can
# neither name a phone nor split the sentence. Single quotes only count when they open at a word boundary, so
# the apostrophe in a phone called "RTK's OnePlus" is left alone.
_QUOTED = re.compile(r'"[^"]*"|\u201c[^\u201d]*\u201d|`[^`]*`|(?<![\w])\'[^\']{2,}?\'(?![\w])')

_LEAD_MODAL = re.compile(r"^(?:(?:should|must|can|could|will|would|to)\s+)*(?:(?:both|each|all)\s+)?", re.IGNORECASE)
_LIST_GAP = re.compile(r"^[\s,&]*(?:and\b)?[\s,]*$", re.IGNORECASE)


def _mask_quotes(text: str) -> str:
    """Same length as `text`, with everything inside quotes blanked so nothing in it can match."""
    chars = list(text)
    for match in _QUOTED.finditer(text):
        for i in range(match.start() + 1, match.end() - 1):
            chars[i] = "_"
    return "".join(chars)


def _norm(value: str) -> str:
    return re.sub(r"\s+", " ", value.translate(_APOSTROPHES)).strip()


@dataclass(frozen=True)
class KnownGroup:
    group_id: str
    name: str
    device_ids: tuple[str, ...]


@dataclass(frozen=True)
class KnownDevice:
    device_id: str
    label: str                    # what to show the person: nickname, else the phone's own name
    labels: tuple[str, ...]       # every phrase that may name it, best first
    paired: bool = True
    connected: bool = True


@dataclass
class Assignment:
    device_id: str
    label: str
    goal: str

    def public(self) -> dict[str, str]:
        return {"deviceId": self.device_id, "label": self.label, "goal": self.goal}


@dataclass
class Plan:
    assignments: list[Assignment] = field(default_factory=list)
    clarification: str | None = None
    confidence: str = "high"          # "high" | "medium"
    needs_confirmation: bool = False
    kind: str = "named"               # "named" | "single" | "broadcast"
    notes: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.clarification is None and bool(self.assignments)

    def public(self) -> dict[str, Any]:
        return {
            "assignments": [a.public() for a in self.assignments],
            "clarification": self.clarification,
            "confidence": self.confidence,
            "needsConfirmation": self.needs_confirmation,
            "kind": self.kind,
            "notes": list(self.notes),
        }


def build_directory(fleet_devices: list[dict[str, Any]], nicknames: dict[str, str]) -> list[KnownDevice]:
    """Everything the person could be referring to.

    Phones that are known only by a nickname (paired once, not connected now) are
    included on purpose: naming one must produce "that phone isn't connected",
    never a silent re-route of its instruction to some other phone.
    """
    known: dict[str, KnownDevice] = {}
    for device in fleet_devices:
        device_id = str(device.get("deviceId") or device.get("id") or "")
        if not device_id:
            continue
        nickname = nicknames.get(device_id)
        candidates = [nickname, device.get("name"), device.get("model")]
        labels: list[str] = []
        for candidate in candidates:
            text = _norm(str(candidate)) if candidate else ""
            if text and text.casefold() not in _GENERIC_LABELS and text.casefold() not in {l.casefold() for l in labels}:
                labels.append(text)
        label = _norm(nickname) if nickname else (labels[0] if labels else device_id[-6:])
        known[device_id] = KnownDevice(
            device_id=device_id, label=label, labels=tuple(labels), paired=bool(device.get("paired")), connected=True,
        )
    for device_id, nickname in nicknames.items():
        if device_id not in known and nickname.strip():
            text = _norm(nickname)
            known[device_id] = KnownDevice(device_id=device_id, label=text, labels=(text,), paired=False, connected=False)
    return list(known.values())


@dataclass
class _Mention:
    start: int                     # start of the phone name (including a leading "the"/"my")
    end: int
    device_ids: tuple[str, ...]
    lead_start: int                # start including "on"/"for"/"tell" - what is removed from the goal
    tail_end: int                  # end including a trailing ":" or "to" after "tell X"
    bare: bool                     # named with no preposition/verb: could be part of the task text


def _label_pattern(label: str) -> re.Pattern[str]:
    """Match a phone label. Punctuation or emoji is escaped as one phrase, never split into words."""
    special = any(not (ch.isalnum() or ch.isspace() or ch == "'") for ch in label)
    if special:
        body = re.escape(label)
    else:
        body = r"\s+".join(re.escape(part) for part in label.split(" ") if part)
    return re.compile(rf"(?<![\w'])(?:(?:the|my)\s+)?{body}(?![\w])", re.IGNORECASE)


def _find_mentions(text: str, directory: list[KnownDevice]) -> list[_Mention]:
    spans: list[tuple[int, int, str, int]] = []   # (start, end, device_id, label_rank)
    for device in directory:
        for rank, label in enumerate(device.labels):
            for match in _label_pattern(label).finditer(text):
                spans.append((match.start(), match.end(), device.device_id, rank))
    # Longest phrase wins ("Tablet Pro" beats "Tablet"); ties prefer a nickname over a model string.
    spans.sort(key=lambda s: (-(s[1] - s[0]), s[3], s[0]))
    taken: list[tuple[int, int]] = []
    accepted: dict[tuple[int, int], set[str]] = {}
    for start, end, device_id, _rank in spans:
        exact = (start, end)
        if exact in accepted:
            accepted[exact].add(device_id)          # same words name two phones -> ambiguous, kept as a set
            continue
        if any(start < t_end and end > t_start for t_start, t_end in taken):
            continue
        taken.append(exact)
        accepted[exact] = {device_id}

    mentions: list[_Mention] = []
    for (start, end), ids in sorted(accepted.items()):
        before = text[:start]
        lead_start, tail_end, bare = start, end, True
        prep = _PREP.search(before)
        verb = _VERB.search(before)
        if prep:
            lead_start, bare = prep.start(), False
        elif verb:
            lead_start, bare = verb.start(), False
            follow = re.match(r"\s*(?:to\b\s*)?", text[end:], re.IGNORECASE)
            if follow:
                tail_end = end + follow.end()
        colon = re.match(r"\s*[:\-\u2013]\s+", text[end:])
        if colon and bare:
            bare, tail_end = False, end + colon.end()
        mentions.append(_Mention(start, end, tuple(sorted(ids)), lead_start, tail_end, bare))
    return mentions


@dataclass
class _Group:
    """Phones named together ("on Work Phone and Tablet") share one instruction."""
    members: list[_Mention]

    @property
    def lead_start(self) -> int:
        return self.members[0].lead_start

    @property
    def tail_end(self) -> int:
        return self.members[-1].tail_end

    @property
    def bare(self) -> bool:
        return self.members[0].bare          # names after the first are list items, not bare mentions


def _group_mentions(text: str, mentions: list[_Mention]) -> list[_Group]:
    groups = [_Group([mentions[0]])]
    for mention in mentions[1:]:
        gap = text[groups[-1].tail_end:mention.lead_start]
        if _LIST_GAP.match(gap) and gap.strip(" ,"):        # only "and" / "," / "&" between two names
            groups[-1].members.append(mention)
        else:
            groups.append(_Group([mention]))
    return groups


def _pick_boundary(gap: str, offset: int, last: bool) -> tuple[int | None, bool, bool]:
    """Where one phone's instruction ends and the next begins, inside the text between two names.

    Returns (absolute boundary index, unambiguous, sequencing_word_seen).
    Stronger separators win: sentence end > comma > a linking word > a bare "and".
    """
    for tier, pattern in ((0, _TIER0), (1, _TIER1), (2, _TIER2), (3, _TIER3)):
        found = list(pattern.finditer(gap))
        if not found:
            continue
        chosen = found[-1] if last else found[0]
        boundary = offset + (chosen.end() if tier in (0, 1) else chosen.start())
        return boundary, len(found) == 1, bool(_SEQUENCING.search(gap))
    return None, False, False


def _clean_goal(segment: str) -> tuple[str, bool]:
    """Tidy what is left of a segment once the phone's name has been cut out."""
    sequencing = False
    text = re.sub(r"\s+", " ", segment).strip()
    while True:
        before = text
        text = _LEAD_JUNK.sub("", text)
        match = _LEAD_CONNECTOR.match(text)
        if match:
            sequencing = sequencing or bool(_SEQUENCING.search(match.group(0)))
            text = text[match.end():]
        if text == before:
            break
    while True:
        before = text
        text = _TRAIL_JUNK.sub("", text)
        text = _TRAIL_CONNECTOR.sub("", text)
        if text == before:
            break
    text = _LEAD_MODAL.sub("", text)
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)
    text = re.sub(r"([,;])\s*([,;])+", r"\1", text).strip(" ,;:")
    if text.endswith("."):
        text = text[:-1].rstrip()
    return (text[:1].upper() + text[1:]) if text else "", sequencing


def _join_goals(goals: list[str]) -> str:
    """Two instructions for the same phone: keep the order, one after the other."""
    parts = [goals[0]] + [g[:1].lower() + g[1:] if g and not g[:2].isupper() else g for g in goals[1:]]
    return ". Then ".join(part.rstrip(".") for part in parts)


def _names(directory: list[KnownDevice], only_paired: bool = False) -> str:
    """Phone list for a clarification. Unpaired phones are named, marked, and never treated as a guess."""
    shown = []
    for device in directory:
        if only_paired and not device.paired:
            continue
        shown.append(device.label if device.paired else f"{device.label} (not paired)")
    return ", ".join(shown) if shown else "none paired yet"


def _resolve_except(tail: str, directory: list[KnownDevice]) -> tuple[list[KnownDevice] | None, str | None]:
    """Phones named after 'except'. Ambiguous or unknown means ask, never guess."""
    mentions = _find_mentions(tail, directory)
    if not mentions:
        return None, "Which phone should be left out? Name it, for example \"except Tablet\"."
    excluded: list[str] = []
    for mention in mentions:
        if len(mention.device_ids) > 1:
            options = " or ".join(directory_label(directory, i) for i in mention.device_ids)
            said = tail[mention.start:mention.end]
            return None, f"\"{said}\" could be {options}. Which one should be left out?"
        excluded.append(mention.device_ids[0])
    if not excluded:
        return None, "Which phone should be left out? Name it, for example \"except Tablet\"."
    kept = [d for d in directory if d.paired and d.device_id not in excluded]
    return kept, None


def directory_label(directory: list[KnownDevice], device_id: str) -> str:
    for device in directory:
        if device.device_id == device_id:
            return device.label
    return device_id


def _match_group(fragment: str, groups: list[KnownGroup]) -> tuple[KnownGroup | None, str | None]:
    """Longest group name that the fragment starts with. Two equal matches is a question."""
    text = _norm(fragment).casefold()
    hits: list[KnownGroup] = []
    for group in groups:
        name = _norm(group.name).casefold()
        if not name:
            continue
        if text == name or text.startswith(name + " ") or text.startswith(name + ",") or text.startswith(name + "."):
            hits.append(group)
    if not hits:
        return None, None
    longest = max(len(_norm(g.name)) for g in hits)
    hits = [g for g in hits if len(_norm(g.name)) == longest]
    if len({g.group_id for g in hits}) > 1:
        options = " or ".join(g.name for g in hits)
        return None, f"\"{hits[0].name}\" could be {options}. Which group did you mean?"
    return hits[0], None


_GROUP_IN = re.compile(
    rf"\b(?:(?:on|for|across|to)\s+)?{_ALL_WORDS}\s+(?:of\s+)?(?:(?:my|the|these|those|our)\s+)?"
    r"(?:phones?|devices?|androids?)\s+in\s+(?:the\s+)?(.+)$",
    re.IGNORECASE,
)
_EXCEPT = re.compile(r"\bexcept\b", re.IGNORECASE)


def split_command(text: str, directory: list[KnownDevice], groups: list[KnownGroup] | None = None) -> Plan:
    """One sentence -> a Plan. Never dispatches; the caller decides what to do with it."""
    text = _norm(text.translate(_APOSTROPHES))
    if not text:
        return Plan(clarification="Tell me what to do, and which phone should do it.")
    by_id = {d.device_id: d for d in directory}
    groups = list(groups or [])
    if not directory and not groups:
        return Plan(clarification="No phones are paired yet. Pair a phone first, then tell it what to do.")

    masked = _mask_quotes(text)
    mentions = _find_mentions(masked, directory)
    plan = Plan()

    broadcast = _BROADCAST.search(masked)
    except_match = _EXCEPT.search(masked, broadcast.end()) if broadcast else None
    # "all phones except Tablet" names Tablet only to leave it out. That is not a per-phone instruction.
    if broadcast and except_match:
        kept, problem = _resolve_except(text[except_match.end():], directory)
        if problem:
            return Plan(clarification=problem)
        excepted = {d.device_id for d in directory if d.paired} - {d.device_id for d in (kept or [])}
        other = [m for m in mentions if not set(m.device_ids) <= excepted and not set(m.device_ids) & excepted]
        if other:
            return Plan(clarification="You named specific phones and also said \"all phones\". Which do you want?")
        tail = text[except_match.end():]
        for mention in reversed(_find_mentions(tail, directory)):
            tail = tail[:mention.lead_start] + " " + tail[mention.tail_end:]
        goal, _sequencing = _clean_goal(text[:broadcast.start()] + " " + text[broadcast.end():except_match.start()] + " " + tail)
        if len(goal) < MIN_GOAL:
            return Plan(clarification="What should all of those phones do?")
        if not kept:
            return Plan(clarification="That leaves no paired phones.")
        plan.kind = "broadcast"
        plan.assignments = [Assignment(d.device_id, d.label, goal) for d in kept]
        plan.needs_confirmation = len(kept) > 1
        plan.confidence = "medium" if len(kept) > 1 else "high"
        plan.notes.append("Every paired phone except the one you named.")
        return plan

    # ---- every phone in a group, before a bare "all phones" -----------------------------------------------
    group_match = _GROUP_IN.search(masked)
    if group_match and not mentions:
        group, ambiguous = _match_group(text[group_match.start(1):], groups)
        if ambiguous:
            return Plan(clarification=ambiguous)
        if group is None:
            known = ", ".join(g.name for g in groups) or "none yet"
            return Plan(clarification=f"I can't find that group. Your groups: {known}.")
        goal, _sequencing = _clean_goal(text[:group_match.start()] + " " + text[group_match.end():])
        if len(goal) < MIN_GOAL:
            return Plan(clarification=f"What should the phones in {group.name} do?")
        members = [by_id[i] for i in group.device_ids if i in by_id and by_id[i].paired]
        if not members:
            return Plan(clarification=f"{group.name} has no paired phones, so nothing would run.")
        plan.kind = "broadcast"
        plan.assignments = [Assignment(d.device_id, d.label, goal) for d in members]
        plan.needs_confirmation = len(members) > 1
        plan.confidence = "medium" if len(members) > 1 else "high"
        plan.notes.append(f"Every paired phone in {group.name}.")
        return plan

    # ---- no phone named -------------------------------------------------------------------------------------
    if not mentions:
        broadcast = _BROADCAST.search(masked)
        paired = [d for d in directory if d.paired]
        if broadcast:
            except_match = _EXCEPT.search(masked, broadcast.end())
            goal_text = text
            if except_match:
                kept, problem = _resolve_except(text[except_match.end():], directory)
                if problem:
                    return Plan(clarification=problem)
                paired = kept or []
                goal_text = text[:except_match.start()] + " " + text[except_match.end():]
                # the excepted name is not part of the goal
                goal, sequencing = _clean_goal(goal_text[:broadcast.start()] + " " + goal_text[broadcast.end():])
            else:
                goal, sequencing = _clean_goal(text[:broadcast.start()] + " " + text[broadcast.end():])
            if len(goal) < MIN_GOAL:
                return Plan(clarification="What should all of those phones do?")
            if not paired:
                return Plan(clarification="No phones are paired yet." if not except_match else
                            "That leaves no paired phones.")
            plan.kind = "broadcast"
            plan.assignments = [Assignment(d.device_id, d.label, goal) for d in paired]
            plan.needs_confirmation = len(paired) > 1     # the same action on several phones is never a surprise-run
            plan.confidence = "medium" if len(paired) > 1 else "high"
            if except_match:
                plan.notes.append("Every paired phone except the one you named.")
            return plan
        if len(paired) == 1:
            goal, _ = _clean_goal(text)
            plan.kind = "single"
            plan.assignments = [Assignment(paired[0].device_id, paired[0].label, goal)]
            plan.notes.append(f"Only one phone is paired, so this goes to {paired[0].label}.")
            return plan
        return Plan(clarification=f"Which phone should do that? Say its name, like \"on {paired[0].label}\". "
                                  f"Your phones: {_names(directory)}.") if paired else Plan(
            clarification="No phones are paired yet. Pair a phone first, then tell it what to do. "
                          f"Your phones: {_names(directory)}.")

    # ---- a name that could mean two phones -------------------------------------------------------------------
    for mention in mentions:
        if len(mention.device_ids) > 1:
            options = " or ".join(by_id[i].label for i in mention.device_ids)
            said = text[mention.start:mention.end]
            return Plan(clarification=f"\"{said}\" could be {options}. Which one did you mean? "
                                      f"Give each phone its own name with Rename so this can't happen.")
    if _BROADCAST.search(masked):
        return Plan(clarification="You named specific phones and also said \"all phones\". Which do you want?")

    # ---- where each phone's instruction begins and ends ------------------------------------------------------
    groups = _group_mentions(masked, mentions)
    prefix_style = bool(_FILLER_ONLY.match(masked[:groups[0].lead_start]))
    boundaries: list[int] = []
    sequencing_seen = False
    for left, right in zip(groups, groups[1:]):
        gap_start, gap_end = left.tail_end, right.lead_start
        boundary, unambiguous, sequencing = _pick_boundary(masked[gap_start:gap_end], gap_start, last=prefix_style)
        if boundary is None:
            a = by_id[left.members[-1].device_ids[0]].label
            b = by_id[right.members[0].device_ids[0]].label
            return Plan(clarification=f"I can't tell where the instruction for {a} ends and {b}'s begins. "
                                      f"Separate them with a comma or the word \"and\".")
        if not unambiguous:
            plan.confidence = "medium"
        sequencing_seen = sequencing_seen or sequencing
        boundaries.append(boundary)

    starts = [0] + boundaries
    ends = boundaries + [len(text)]
    goals_by_device: dict[str, list[str]] = {}
    order: list[str] = []
    for group, seg_start, seg_end in zip(groups, starts, ends):
        # Cut the phones' names (and their "on"/"tell ... to") out of this segment; what's left is the instruction.
        remainder = text[seg_start:group.lead_start] + " " + text[group.tail_end:seg_end]
        goal, sequencing = _clean_goal(remainder)
        sequencing_seen = sequencing_seen or sequencing
        if len(goal) < MIN_GOAL:
            name = by_id[group.members[-1].device_ids[0]].label
            return Plan(clarification=f"What should {name} do? I only found its name, not an instruction.")
        for member in group.members:
            device_id = member.device_ids[0]
            if device_id not in goals_by_device:
                goals_by_device[device_id] = []
                order.append(device_id)
            goals_by_device[device_id].append(goal)
        if group.bare:
            plan.confidence = "medium"      # a name with no "on"/"tell": might be a word inside the task instead

    plan.assignments = [
        Assignment(device_id, by_id[device_id].label,
                   goals_by_device[device_id][0] if len(goals_by_device[device_id]) == 1 else _join_goals(goals_by_device[device_id]))
        for device_id in order
    ]
    if any(len(v) > 1 for v in goals_by_device.values()):
        plan.notes.append("One phone was named more than once; its instructions run in the order you gave them.")
    unpaired = [by_id[i].label for i in order if not by_id[i].paired]
    if unpaired:
        plan.notes.append("Named but not paired: " + ", ".join(f"{name} (not paired)" for name in unpaired) + ".")
    if sequencing_seen and len(plan.assignments) > 1:
        plan.notes.append("\"then\" was read as \"and\": the phones start at the same time. Ordering one phone's work "
                          "after another's isn't supported yet.")
    plan.needs_confirmation = plan.confidence != "high"
    return plan
