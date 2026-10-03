"""Pages as plain Markdown for the Command Center's AI (plan 33 §7 + C5).

The AI reads a page as Markdown with block ids, and writes simple Markdown that becomes typed blocks here. It never
writes block JSON, so everything it adds passes the same checks as the owner's typing (``pages.validate_blocks``):
no markup, no secrets, references only to things that exist.

Markdown it may write, one block per line:
``# `` ``## `` ``### `` headings, ``- `` bullets, ``1. `` numbers, ``- [ ] `` / ``- [x] `` to-dos, ``> `` quotes,
``! `` callouts, ``---`` dividers, anything else a paragraph. Inline: ``**bold**``, ``*italic*``, ```code```,
``~~struck~~`` and mentions ``@[kind:id]`` (page, device, routine, task, account, connection).
"""
from __future__ import annotations

import re
from datetime import datetime
from typing import Any, Callable

from .center import CommandError

MENTION_KINDS = ("page", "device", "routine", "task", "account", "connection")
MAX_MARKDOWN = 20_000
MAX_NEW_BLOCKS = 200

_INLINE = re.compile(
    r"\*\*(?P<b>.+?)\*\*"
    r"|~~(?P<s>.+?)~~"
    r"|`(?P<c>[^`]+)`"
    r"|(?<![\w*])\*(?P<i>[^*\s](?:[^*]*[^*\s])?)\*(?![\w*])"
    r"|@\[(?P<kind>" + "|".join(MENTION_KINDS) + r"):(?P<id>[^\]|\s]{1,120})(?:\|(?P<label>[^\]]{0,120}))?\]"
)
_LINE = (
    (re.compile(r"^###\s+(.*)$"), "h3"),
    (re.compile(r"^##\s+(.*)$"), "h2"),
    (re.compile(r"^#\s+(.*)$"), "h1"),
    (re.compile(r"^[-*]\s+\[\s\]\s+(.*)$"), "todo"),
    (re.compile(r"^[-*]\s+\[[xX]\]\s+(.*)$"), "done"),
    (re.compile(r"^[-*•]\s+(.*)$"), "bullet"),
    (re.compile(r"^\d{1,3}[.)]\s+(.*)$"), "number"),
    (re.compile(r"^>\s?(.*)$"), "quote"),
    (re.compile(r"^!\s+(.*)$"), "callout"),
)
_DIVIDER = re.compile(r"^(?:-{3,}|\*{3,}|_{3,})$")

#: Looks a referenced thing's label up by id; returns None when there is no such thing.
Lookup = Callable[[str, str], "str | None"]


def spans(text: str, lookup: Lookup) -> list[dict[str, Any]]:
    """One line of inline Markdown as typed text pieces."""
    out: list[dict[str, Any]] = []
    at = 0
    for match in _INLINE.finditer(text):
        if match.start() > at:
            out.append({"t": text[at:match.start()]})
        at = match.end()
        if match.group("kind"):
            kind, ident = match.group("kind"), match.group("id")
            label = lookup(kind, ident)
            if label is None:
                raise CommandError(f"There is no {kind} {ident}. Look ids up with the list and search tools; never guess them.")
            out.append({"ref": {"kind": kind, "id": ident, "label": (match.group("label") or label or ident).strip()[:120]}})
            continue
        for mark in ("b", "s", "c", "i"):
            if match.group(mark) is not None:
                out.append({"t": match.group(mark), mark: True})
                break
    if at < len(text):
        out.append({"t": text[at:]})
    return [s for s in out if "ref" in s or s["t"]]


def blocks_from_markdown(markdown: Any, lookup: Lookup) -> list[dict[str, Any]]:
    """Markdown the AI wrote as new blocks (not yet validated; ``pages.validate_blocks`` does that)."""
    if not isinstance(markdown, str) or not markdown.strip():
        raise CommandError("content is Markdown text.")
    if len(markdown) > MAX_MARKDOWN:
        raise CommandError(f"content is at most {MAX_MARKDOWN} characters; add long pages in parts.")
    blocks: list[dict[str, Any]] = []
    fenced = False
    for raw in markdown.replace("\r\n", "\n").split("\n"):
        line = raw.rstrip()
        if line.strip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            if line.strip():
                blocks.append({"type": "p", "text": [{"t": line, "c": True}]})
            continue
        line = line.strip()
        if not line:
            continue
        if _DIVIDER.match(line):
            blocks.append({"type": "divider"})
            continue
        for pattern, kind in _LINE:
            m = pattern.match(line)
            if m:
                body = m.group(1)
                if kind == "done":
                    blocks.append({"type": "todo", "checked": True, "text": spans(body, lookup)})
                elif kind == "todo":
                    blocks.append({"type": "todo", "checked": False, "text": spans(body, lookup)})
                elif kind == "callout":
                    blocks.append({"type": "callout", "icon": "💡", "text": spans(body, lookup)})
                else:
                    blocks.append({"type": kind, "text": spans(body, lookup)})
                break
        else:
            blocks.append({"type": "p", "text": spans(line, lookup)})
        if len(blocks) > MAX_NEW_BLOCKS:
            raise CommandError(f"Add at most {MAX_NEW_BLOCKS} blocks at a time.")
    if not blocks:
        raise CommandError("content has no blocks.")
    return blocks


_MENTION = re.compile(r"@\[(?P<kind>" + "|".join(MENTION_KINDS) + r"):(?P<id>[^\]|\s]{1,120})(?:\|(?P<label>[^\]]{0,120}))?\]")


def label_mentions(text: str, lookup: Lookup) -> str:
    """Mentions with their current names (``@[routine:rtn_x|Answer orders]``), so the owner reads names, not ids. A
    mention of something that does not exist becomes plain text."""
    def swap(match: re.Match[str]) -> str:
        label = lookup(match.group("kind"), match.group("id"))
        if label is None:
            return (match.group("label") or match.group("id")).strip()
        return f"@[{match.group('kind')}:{match.group('id')}|{label.replace(']', ')')[:120]}]"
    return _MENTION.sub(swap, text)


def inline_markdown(text: list[dict[str, Any]]) -> str:
    parts = []
    for span in text:
        if "ref" in span:
            ref = span["ref"]
            parts.append(f"@[{ref['kind']}:{ref['id']}|{ref['label']}]")
            continue
        t = span["t"]
        if span.get("c"):
            t = f"`{t}`"
        if span.get("b"):
            t = f"**{t}**"
        if span.get("i"):
            t = f"*{t}*"
        if span.get("s"):
            t = f"~~{t}~~"
        parts.append(t)
    return "".join(parts)


_PREFIX = {"h1": "# ", "h2": "## ", "h3": "### ", "bullet": "- ", "number": "1. ", "quote": "> ", "p": ""}


def _day(ms: Any) -> str:
    return datetime.fromtimestamp(ms / 1000).astimezone().strftime("%Y-%m-%d") if isinstance(ms, int) else ""


def page_markdown(page: dict[str, Any], *, limit: int = 30_000) -> str:
    """A page as Markdown, each block tagged with its id so the AI can name it in an edit."""
    lines = [f"# {page.get('icon') or ''} {page['title']}".replace("#  ", "# "), ""]
    for block in page["blocks"]:
        tag = f"[{block['id']}] "
        kind = block["type"]
        if kind == "divider":
            lines.append(tag + "---")
        elif kind == "todo":
            lines.append(tag + ("- [x] " if block.get("checked") else "- [ ] ") + inline_markdown(block["text"]))
        elif kind == "callout":
            lines.append(tag + "! " + inline_markdown(block["text"]))
        elif kind in _PREFIX:
            lines.append(tag + _PREFIX[kind] + inline_markdown(block["text"]))
        elif kind == "ref":
            ref = block["ref"]
            lines.append(tag + f"(card) @[{ref['kind']}:{ref['id']}|{ref['label']}]")
        elif kind == "view":
            flt = ", ".join(f"{k}={v}" for k, v in sorted(block.get("filter", {}).items()))
            lines.append(tag + f"(live view of {block['source']} as a {block['layout']}{': ' + flt if flt else ''}; read it with the list tools)")
        elif kind == "board":
            lines.append(tag + f"(plan board \"{block.get('title') or 'Plan'}\", shown as a {block['layout']}; {len(block['items'])} cards)")
            for item in block["items"]:
                extra = []
                if item.get("due"):
                    extra.append(f"due {_day(item['due'])}")
                if item.get("refs"):
                    extra.append("links " + " ".join(f"@[{r['kind']}:{r['id']}|{r['label']}]" for r in item["refs"]))
                if item.get("taskId"):
                    extra.append(f"task @[task:{item['taskId']}]")
                if item.get("note"):
                    extra.append("note: " + item["note"].replace("\n", " ")[:300])
                lines.append(f"    - [{item['id']}] ({item['status']}) {item['title']}" + (" — " + "; ".join(extra) if extra else ""))
    text = "\n".join(lines)
    if len(text) > limit:
        text = text[:limit] + "\n… (the page continues; it is too long to read in full)"
    return text
