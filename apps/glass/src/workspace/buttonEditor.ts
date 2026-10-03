/**
 * The button editor (plan 43 T3): what a table button does, where it runs and what it writes back. Glass only shapes the
 * settings; the gateway checks them against the table and runs the button.
 */
import type { GlassContext } from "../app.js";
import { command } from "../services/command.js";
import {
  BUTTON_ACTIONS, COLORS, COMPUTED, optionOf, type ButtonAction, type OptionColor, type PropConfig, type RunOn, type Table, type TableProp,
} from "../services/tables.js";
import { el, setChildren } from "../ui/dom.js";

export interface ButtonEditor {
  element: HTMLElement;
  /** The settings to save; throws with a message when something is missing. */
  read(): PropConfig;
}

const TOKENS = ["@today", "@now"];

export function createButtonEditor(ctx: GlassContext, table: Table, existing?: TableProp): ButtonEditor {
  const cfg = existing?.config ?? {};
  const element = el("div", "tb-button-editor");
  const others = table.properties.filter((p) => p.id !== existing?.id);
  const editable = others.filter((p) => !COMPUTED.includes(p.type) && p.type !== "title");

  const label = input("Button label", cfg.label ?? "Run");
  const color = select("Colour", COLORS.map((c) => [c, c[0].toUpperCase() + c.slice(1)]), cfg.color ?? "purple");
  const actionsBox = el("div", "tb-button-actions");
  const rows: Array<() => ButtonAction | null> = [];
  const addAction = (action?: ButtonAction) => {
    const row = el("div", "tb-button-action");
    const kind = select("Action", BUTTON_ACTIONS.map((a) => [a.id, a.label]), action?.do ?? "prompt");
    const fields = el("div", "tb-button-fields");
    const remove = el("button", "tb-icon-btn", "×");
    remove.type = "button";
    remove.title = "Remove this action";
    let read: () => ButtonAction | null = () => null;
    const draw = () => {
      const k = kind.value as ButtonAction["do"];
      const same = action && action.do === k ? action : undefined;
      if (k === "prompt" || k === "skill") {
        const skill = input("Saved skill name", same?.do === "skill" ? same.skill : "");
        const prompt = el("textarea", "tb-input tb-formula");
        prompt.setAttribute("aria-label", "Prompt");
        prompt.placeholder = k === "prompt" ? "Check WhatsApp for messages from {{Customer}} and summarise them" : "Inputs (optional), e.g. for {{Name}}";
        prompt.value = same && "prompt" in same ? same.prompt : "";
        const hint = el("p", "tb-muted tb-small-text", `Fill from the row with {{${others[0]?.name ?? "Name"}}}; through a relation with {{Relation.Property}}.`);
        setChildren(fields, ...(k === "skill" ? [skill] : []), prompt, hint);
        read = () => k === "skill"
          ? { do: "skill", skill: String(skill.value ?? "").trim(), prompt: String(prompt.value ?? "").trim() }
          : { do: "prompt", prompt: String(prompt.value ?? "").trim() };
      } else if (k === "routine") {
        const routine = select("Routine", [["", "Loading routines…"]], "");
        void command.routines(ctx.client).then((list) => {
          setChildren(routine, ...list.map((r) => option(r.id, r.title)));
          if (!list.length) routine.append(option("", "No routines yet: make one in Routines"));
          if (same?.do === "routine") routine.value = same.routineId;
        }).catch(() => setChildren(routine, option("", "Routines could not be loaded")));
        setChildren(fields, routine);
        read = () => ({ do: "routine", routineId: String(routine.value ?? "") });
      } else if (k === "update") {
        const current = same?.do === "update" ? Object.entries(same.cells)[0] : undefined;
        const prop = select("Property", editable.map((p) => [p.id, p.name]), current?.[0] ?? editable[0]?.id ?? "");
        const valueBox = el("span");
        let value: () => unknown = () => null;
        const drawValue = () => {
          const p = editable.find((x) => x.id === prop.value);
          const was = current && current[0] === prop.value ? current[1] : undefined;
          if (!p) {
            setChildren(valueBox);
            value = () => null;
          } else if (p.type === "select" || p.type === "status") {
            const s = select("Value", (p.config.options ?? []).map((o) => [o.id, o.name]), typeof was === "string" ? was : "");
            setChildren(valueBox, s);
            value = () => String(s.value ?? "");
          } else if (p.type === "checkbox") {
            const s = select("Value", [["true", "Checked"], ["false", "Unchecked"]], was === false ? "false" : "true");
            setChildren(valueBox, s);
            value = () => s.value === "true";
          } else if (p.type === "date") {
            const s = select("Value", [["@today", "Today"], ["@now", "Now (date and time)"]], typeof was === "string" && TOKENS.includes(was) ? was : "@today");
            setChildren(valueBox, s);
            value = () => String(s.value);
          } else if (p.type === "number" || p.type === "currency" || p.type === "percent") {
            const i = input("Value", typeof was === "number" ? String(was) : "");
            i.type = "number";
            setChildren(valueBox, i);
            value = () => Number(i.value);
          } else {
            const i = input("Value", typeof was === "string" ? was : "");
            setChildren(valueBox, i);
            value = () => String(i.value ?? "");
          }
        };
        prop.addEventListener("change", drawValue);
        drawValue();
        setChildren(fields, prop, valueBox);
        read = () => (prop.value ? { do: "update", cells: { [String(prop.value)]: value() } } : null);
      } else {
        const url = input("Link", same?.do === "open" ? same.url : "https://");
        setChildren(fields, url);
        read = () => ({ do: "open", url: String(url.value ?? "").trim() });
      }
    };
    kind.addEventListener("change", () => {
      action = undefined;
      draw();
    });
    draw();
    const index = rows.length;
    rows.push(() => read());
    remove.addEventListener("click", () => {
      rows[index] = () => null;
      row.remove();
    });
    row.append(kind, fields, remove);
    actionsBox.append(row);
  };
  for (const action of cfg.actions ?? []) addAction(action);
  if (!(cfg.actions ?? []).length) addAction();
  const more = el("button", "tb-menu-row", "+ Add an action");
  more.type = "button";
  more.addEventListener("click", () => addAction());

  // Where it runs.
  const phones = table.properties.filter((p) => p.type === "relation" && p.config.target === "sys:phones");
  const whereOptions: Array<[string, string]> = [["any", "Any ready phone"], ...ctx.devices.map((d): [string, string] => [`phone:${d.id}`, d.name]),
    ...phones.map((p): [string, string] => [`row:${p.id}`, `The row's ${p.name}`])];
  const on = cfg.runOn ?? { kind: "any" };
  const where = select("Runs on", whereOptions, on.kind === "phone" ? `phone:${on.deviceId}` : on.kind === "row" ? `row:${on.propId}` : "any");

  // After the run.
  const statuses = editable.filter((p) => p.type === "status" || p.type === "select");
  const texts = others.filter((p) => p.type === "text");
  const statusProp = statuses[0];
  const thenCells = cfg.then ?? {};
  const onSuccess = statusProp ? select(`On success, ${statusProp.name}`, [["", "Leave it"], ...(statusProp.config.options ?? []).map((o): [string, string] => [o.id, o.name])],
    typeof thenCells.success?.[statusProp.id] === "string" ? String(thenCells.success[statusProp.id]) : "") : null;
  const onFailure = statusProp ? select(`On failure, ${statusProp.name}`, [["", "Leave it"], ...(statusProp.config.options ?? []).map((o): [string, string] => [o.id, o.name])],
    typeof thenCells.failure?.[statusProp.id] === "string" ? String(thenCells.failure[statusProp.id]) : "") : null;
  const summary = select("Write the result into", [["", "Nowhere"], ...texts.map((p): [string, string] => [p.id, p.name])], thenCells.summary ?? "");

  element.append(
    labelled("Label", label), labelled("Colour", color),
    el("div", "tb-menu-title", "Does"), actionsBox, more,
    labelled("Runs on", where),
    el("div", "tb-menu-title", "After the run"),
    ...(onSuccess ? [labelled(`On success, set ${statusProp!.name} to`, onSuccess)] : []),
    ...(onFailure ? [labelled(`On failure, set ${statusProp!.name} to`, onFailure)] : []),
    labelled("Write the result into", summary),
    el("p", "tb-muted tb-small-text", "A button only starts work. The phone still asks you before paying, sending, deleting or signing in."),
  );

  return {
    element,
    read(): PropConfig {
      const text = String(label.value ?? "").trim();
      if (!text) throw new Error("Give the button a label.");
      const actions = rows.map((r) => r()).filter((a): a is ButtonAction => a !== null);
      if (!actions.length) throw new Error("Add at least one action.");
      for (const a of actions) {
        if ((a.do === "prompt") && !a.prompt) throw new Error("Write what the phone should do.");
        if (a.do === "skill" && !a.skill) throw new Error("Name the saved skill.");
        if (a.do === "routine" && !a.routineId) throw new Error("Pick a routine.");
        if (a.do === "open" && !/^https?:\/\/\S+\.\S+/.test(a.url)) throw new Error("Open takes an http(s) link.");
      }
      const w = String(where.value ?? "any");
      const runOn: RunOn = w.startsWith("phone:") ? { kind: "phone", deviceId: w.slice(6) } : w.startsWith("row:") ? { kind: "row", propId: w.slice(4) } : { kind: "any" };
      const then: NonNullable<PropConfig["then"]> = {};
      if (statusProp && onSuccess?.value) then.success = { [statusProp.id]: String(onSuccess.value) };
      if (statusProp && onFailure?.value) then.failure = { [statusProp.id]: String(onFailure.value) };
      if (summary.value) then.summary = String(summary.value);
      return { label: text, color: String(color.value) as OptionColor, actions, runOn, then };
    },
  };
}

/** "Check WhatsApp · Done ✓" etc. for a button's latest run. */
export function describeActions(prop: TableProp, table: Table): string {
  return (prop.config.actions ?? []).map((a) => {
    if (a.do === "prompt") return `Asks: ${a.prompt.slice(0, 60)}`;
    if (a.do === "skill") return `Skill ${a.skill}`;
    if (a.do === "routine") return "Runs a routine";
    if (a.do === "open") return `Opens ${a.url.replace(/^https?:\/\//, "").slice(0, 40)}`;
    return Object.entries(a.cells).map(([id, v]) => {
      const p = table.properties.find((x) => x.id === id);
      return `Sets ${p?.name ?? "a property"}${p && typeof v === "string" && optionOf(p, v) ? ` → ${optionOf(p, v)!.name}` : ""}`;
    }).join(", ");
  }).join(" · ");
}

function input(labelText: string, value: string): HTMLInputElement {
  const i = el("input", "tb-input");
  i.setAttribute("aria-label", labelText);
  i.value = value;
  return i;
}

function option(value: string, text: string): HTMLOptionElement {
  const o = el("option", "", text);
  o.value = value;
  return o;
}

function select(labelText: string, options: Array<[string, string]>, value: string): HTMLSelectElement {
  const s = el("select", "tb-select");
  s.setAttribute("aria-label", labelText);
  for (const [v, t] of options) s.append(option(v, t));
  if (value) s.value = value;
  return s;
}

function labelled(text: string, control: HTMLElement): HTMLElement {
  const wrap = el("label", "tb-field");
  wrap.append(el("span", "tb-muted", text), control);
  return wrap;
}
