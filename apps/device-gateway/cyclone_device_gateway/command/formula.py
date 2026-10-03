"""Formulas for tables (plan 43, T2): a small, safe expression language evaluated on the PC, never by a model and never
with ``eval``. It reads the row's own properties by name and returns a number, text, a yes/no or a date.

    prop("Estimate") * 1.21
    if(prop("Status") == "Payment Made", "paid", "open")
    dateBetween(prop("Refund date"), now(), "days")
    concat(prop("Bank"), " | ", round(prop("Estimate")))

Grammar (precedence low to high): or, and, not, comparisons, + -, * / %, unary minus, calls and literals.
"""
from __future__ import annotations

import math
import re
from datetime import date, datetime, timezone
from typing import Any, Callable

MAX_LENGTH = 1_000
MAX_DEPTH = 40

TOKEN = re.compile(r"""\s*(?:
    (?P<number>\d+(?:\.\d+)?)
  | (?P<string>"(?:[^"\\]|\\.)*")
  | (?P<op>==|!=|>=|<=|[-+*/%(),<>])
  | (?P<name>[A-Za-z_][A-Za-z0-9_]*)
)""", re.X)


class FormulaError(ValueError):
    """The formula can't be read or can't be worked out."""


def tokenize(text: str) -> list[tuple[str, Any]]:
    if not isinstance(text, str) or not text.strip():
        raise FormulaError("Write a formula.")
    if len(text) > MAX_LENGTH:
        raise FormulaError(f"A formula is at most {MAX_LENGTH} characters.")
    out: list[tuple[str, Any]] = []
    at = 0
    while at < len(text):
        if text[at:].strip() == "":
            break
        m = TOKEN.match(text, at)
        if not m or m.end() == at:
            raise FormulaError(f"I can't read the formula near “{text[at:at + 12].strip()}”.")
        at = m.end()
        kind = m.lastgroup
        value: Any = m.group(kind)  # type: ignore[arg-type]
        if kind == "number":
            value = float(value)
        elif kind == "string":
            value = re.sub(r"\\(.)", r"\1", value[1:-1])
        out.append((kind, value))  # type: ignore[arg-type]
    return out


# ------------------------------------------------------------------------------------------------ parsing to a tree

class _Parser:
    def __init__(self, tokens: list[tuple[str, Any]]):
        self.t = tokens
        self.i = 0

    def peek(self) -> tuple[str, Any] | None:
        return self.t[self.i] if self.i < len(self.t) else None

    def take(self, kind: str | None = None, value: Any = None) -> tuple[str, Any]:
        tok = self.peek()
        if tok is None or (kind and tok[0] != kind) or (value is not None and tok[1] != value):
            want = value if value is not None else kind
            raise FormulaError(f"Expected {want} {'at the end' if tok is None else f'before “{tok[1]}”'}.")
        self.i += 1
        return tok

    def at(self, kind: str, value: Any = None) -> bool:
        tok = self.peek()
        return tok is not None and tok[0] == kind and (value is None or tok[1] == value)

    def parse(self) -> tuple:
        node = self.or_()
        if self.peek() is not None:
            raise FormulaError(f"Unexpected “{self.peek()[1]}”.")  # type: ignore[index]
        return node

    def or_(self) -> tuple:
        node = self.and_()
        while self.at("name", "or"):
            self.take()
            node = ("or", node, self.and_())
        return node

    def and_(self) -> tuple:
        node = self.not_()
        while self.at("name", "and"):
            self.take()
            node = ("and", node, self.not_())
        return node

    def not_(self) -> tuple:
        if self.at("name", "not"):
            self.take()
            return ("not", self.not_())
        return self.cmp()

    def cmp(self) -> tuple:
        node = self.add()
        tok = self.peek()
        if tok and tok[0] == "op" and tok[1] in ("==", "!=", ">", "<", ">=", "<="):
            self.take()
            node = ("cmp", tok[1], node, self.add())
        return node

    def add(self) -> tuple:
        node = self.mul()
        while self.at("op", "+") or self.at("op", "-"):
            op = self.take()[1]
            node = ("bin", op, node, self.mul())
        return node

    def mul(self) -> tuple:
        node = self.unary()
        while self.at("op", "*") or self.at("op", "/") or self.at("op", "%"):
            op = self.take()[1]
            node = ("bin", op, node, self.unary())
        return node

    def unary(self) -> tuple:
        if self.at("op", "-"):
            self.take()
            return ("neg", self.unary())
        return self.primary()

    def primary(self) -> tuple:
        tok = self.peek()
        if tok is None:
            raise FormulaError("The formula ends too early.")
        if tok[0] == "number":
            self.take()
            return ("lit", tok[1])
        if tok[0] == "string":
            self.take()
            return ("lit", tok[1])
        if self.at("op", "("):
            self.take()
            node = self.or_()
            self.take("op", ")")
            return node
        if tok[0] == "name":
            self.take()
            name = tok[1]
            if name in ("true", "false"):
                return ("lit", name == "true")
            if not self.at("op", "("):
                raise FormulaError(f"“{name}” needs brackets: {name}(…). Properties are prop(\"Name\").")
            self.take()
            args: list[tuple] = []
            if not self.at("op", ")"):
                args.append(self.or_())
                while self.at("op", ","):
                    self.take()
                    args.append(self.or_())
            self.take("op", ")")
            if name not in FUNCTIONS and name != "prop":
                raise FormulaError(f"There is no function “{name}”.")
            if name == "prop":
                if len(args) != 1 or args[0][0] != "lit" or not isinstance(args[0][1], str):
                    raise FormulaError('prop takes a property name in quotes: prop("Name").')
                return ("prop", args[0][1])
            low, high = ARITY[name]
            if not low <= len(args) <= high:
                raise FormulaError(f"{name} takes {low if low == high else f'{low} to {high}'} values.")
            return ("call", name, args)
        raise FormulaError(f"Unexpected “{tok[1]}”.")


def parse(text: str) -> tuple:
    return _Parser(tokenize(text)).parse()


def names(tree: tuple) -> set[str]:
    """The property names a formula reads."""
    out: set[str] = set()

    def walk(node: Any) -> None:
        if not isinstance(node, tuple):
            return
        if node[0] == "prop":
            out.add(node[1])
            return
        for part in node[1:]:
            if isinstance(part, list):
                for item in part:
                    walk(item)
            else:
                walk(part)
    walk(tree)
    return out


def rename(text: str, old: str, new: str) -> str:
    """A property was renamed: its prop("…") references follow."""
    escaped = new.replace("\\", "\\\\").replace('"', '\\"')
    return re.sub(r'prop\(\s*"((?:[^"\\]|\\.)*)"\s*\)',
                  lambda m: f'prop("{escaped}")' if re.sub(r"\\(.)", r"\1", m.group(1)) == old else m.group(0), text)


# ------------------------------------------------------------------------------------------------ evaluation

def _day(value: Any) -> date | None:
    if isinstance(value, dict) and isinstance(value.get("start"), str):
        value = value["start"]
    if isinstance(value, str) and re.match(r"^\d{4}-\d{2}-\d{2}", value):
        try:
            return date.fromisoformat(value[:10])
        except ValueError:
            return None
    return None


def _num(value: Any, fn: str) -> float:
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, (int, float)):
        return float(value)
    if value is None:
        return 0.0
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError as exc:
            raise FormulaError(f"{fn} needs a number, not “{value[:30]}”.") from exc
    raise FormulaError(f"{fn} needs a number.")


def text_of(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return str(int(value)) if value.is_integer() else f"{value:.10g}"
    if isinstance(value, dict) and "start" in value:
        return value["start"] + (f" → {value['end']}" if value.get("end") else "")
    if isinstance(value, list):
        return ", ".join(text_of(v) for v in value)
    return str(value)


def _truthy(value: Any) -> bool:
    if isinstance(value, (list, str)):
        return len(value) > 0
    return bool(value)


def _between(a: Any, b: Any, unit: Any) -> float:
    da, db = _day(a), _day(b)
    if da is None or db is None:
        raise FormulaError("dateBetween needs two dates.")
    days = (da - db).days
    return float({"days": days, "weeks": days // 7, "months": (da.year - db.year) * 12 + da.month - db.month,
                  "years": da.year - db.year}.get(str(unit), days))


FUNCTIONS: dict[str, Callable[..., Any]] = {
    "concat": lambda *a: "".join(text_of(v) for v in a),
    "round": lambda x, n=0.0: round(_num(x, "round"), int(_num(n, "round"))),
    "floor": lambda x: float(math.floor(_num(x, "floor"))),
    "ceil": lambda x: float(math.ceil(_num(x, "ceil"))),
    "abs": lambda x: abs(_num(x, "abs")),
    "min": lambda *a: min(_num(v, "min") for v in a),
    "max": lambda *a: max(_num(v, "max") for v in a),
    "length": lambda x: float(len(x) if isinstance(x, list) else len(text_of(x))),
    "lower": lambda x: text_of(x).lower(),
    "upper": lambda x: text_of(x).upper(),
    "contains": lambda x, s: text_of(s).lower() in text_of(x).lower(),
    "empty": lambda x: not _truthy(x) and x != 0,
    "format": lambda x: text_of(x),
    "toNumber": lambda x: _num(x, "toNumber"),
    "dateBetween": _between,
}
ARITY: dict[str, tuple[int, int]] = {
    "if": (3, 3), "concat": (1, 20), "round": (1, 2), "floor": (1, 1), "ceil": (1, 1), "abs": (1, 1), "min": (1, 20), "max": (1, 20),
    "length": (1, 1), "lower": (1, 1), "upper": (1, 1), "contains": (2, 2), "empty": (1, 1), "format": (1, 1), "toNumber": (1, 1),
    "dateBetween": (2, 3), "now": (0, 0),
}
FUNCTIONS["if"] = lambda c, a, b: a if _truthy(c) else b  # evaluated lazily below
FUNCTIONS["now"] = lambda: None  # replaced below with the clock


def evaluate(tree: tuple, read: Callable[[str], Any], *, today: str | None = None, depth: int = 0) -> Any:
    """Works out a parsed formula for one row. [read] gives a property's value by name (FormulaError if unknown)."""
    if depth > MAX_DEPTH:
        raise FormulaError("The formula is nested too deep.")
    kind = tree[0]
    ev = lambda node: evaluate(node, read, today=today, depth=depth + 1)  # noqa: E731
    if kind == "lit":
        return tree[1]
    if kind == "prop":
        return read(tree[1])
    if kind == "neg":
        return -_num(ev(tree[1]), "-")
    if kind == "not":
        return not _truthy(ev(tree[1]))
    if kind == "and":
        return _truthy(ev(tree[1])) and _truthy(ev(tree[2]))
    if kind == "or":
        return _truthy(ev(tree[1])) or _truthy(ev(tree[2]))
    if kind == "bin":
        op, a, b = tree[1], ev(tree[2]), ev(tree[3])
        if op == "+" and (isinstance(a, str) or isinstance(b, str)):
            return text_of(a) + text_of(b)
        x, y = _num(a, op), _num(b, op)
        if op in ("/", "%") and y == 0:
            return None
        return {"+": x + y, "-": x - y, "*": x * y, "/": x / y if y else None, "%": x % y if y else None}[op]
    if kind == "cmp":
        op, a, b = tree[1], ev(tree[2]), ev(tree[3])
        if isinstance(a, (int, float)) and isinstance(b, (int, float)) and not isinstance(a, bool) and not isinstance(b, bool):
            x: Any = float(a)
            y: Any = float(b)
        elif _day(a) and _day(b):
            x, y = _day(a), _day(b)
        else:
            x, y = text_of(a).lower(), text_of(b).lower()
        return {"==": x == y, "!=": x != y, ">": x > y, "<": x < y, ">=": x >= y, "<=": x <= y}[op]
    if kind == "call":
        name, args = tree[1], tree[2]
        if name == "if":
            return ev(args[1]) if _truthy(ev(args[0])) else ev(args[2])
        if name == "now":
            return {"start": today or datetime.now(timezone.utc).strftime("%Y-%m-%d")}
        return FUNCTIONS[name](*[ev(a) for a in args])
    raise FormulaError("Unknown formula part.")


def result(value: Any) -> Any:
    """A formula's value as a cell: numbers are finite, text is short, anything else becomes text."""
    if value is None or isinstance(value, bool) or isinstance(value, str) and len(value) <= 2_000:
        return value
    if isinstance(value, (int, float)):
        return float(value) if math.isfinite(value) else None
    if isinstance(value, dict) and "start" in value:
        return {"start": value["start"]}
    return text_of(value)[:2_000]
