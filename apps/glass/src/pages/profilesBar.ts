/**
 * Profiles under a phone (plan 43 T4): Profile A and the profiles Cyclone made, the one in front, a Switch button that
 * puts the phone in another profile from the PC, and an app manager for a Cyclone profile (add an app Profile A has,
 * remove one from that profile only). Creating and deleting profiles stays on the phone.
 */
import type { GlassContext } from "../app.js";
import { MAIN_PROFILE, cssColor, profilesApi, type PhoneProfile, type ProfileApps } from "../services/profiles.js";
import { el, setChildren } from "../ui/dom.js";
import { actionButton, chip } from "../ui/components.js";

export interface ProfilesBar {
  element: HTMLElement;
  /** Loads the phone's profiles (a new phone, or after a change). */
  load(deviceId: string | null): Promise<void>;
  destroy(): void;
}

/** [onSelect] gets the chosen profile and, for a Cyclone profile, the packages it has (null = all of the phone's). */
export function createProfilesBar(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void,
  onSelect: (profileId: string, packages: Set<string> | null) => void): ProfilesBar {
  const element = el("div", "pf");
  const strip = el("div", "pf-strip");
  strip.setAttribute("role", "tablist");
  strip.setAttribute("aria-label", "Profiles");
  const actions = el("div", "pf-actions");
  const manager = el("div", "pf-manager");
  element.append(strip, actions, manager);
  let device: string | null = null;
  let profiles: PhoneProfile[] = [];
  let selected = MAIN_PROFILE;
  let apps: ProfileApps | null = null;
  let managing = false;
  let busy = false;
  let seq = 0;
  let destroyed = false;

  async function load(next: string | null): Promise<void> {
    if (next !== device) {
      device = next;
      selected = MAIN_PROFILE;
      apps = null;
      managing = false;
    }
    const mine = ++seq;
    if (!device) {
      element.hidden = true;
      return;
    }
    try {
      const result = await profilesApi.list(ctx.client, device);
      if (destroyed || mine !== seq) return;
      profiles = result.profiles.filter((p) => !p.inTrash);
      element.hidden = profiles.length < 2;
      if (!profiles.some((p) => p.id === selected)) selected = result.current ?? MAIN_PROFILE;
    } catch {
      // An older phone or no root: the phone is shown as one profile, as before.
      if (destroyed || mine !== seq) return;
      profiles = [];
      element.hidden = true;
    }
    await loadApps();
    render();
  }

  async function loadApps(): Promise<void> {
    if (!device || selected === MAIN_PROFILE) {
      apps = null;
      onSelect(selected, null);
      return;
    }
    try {
      apps = await profilesApi.apps(ctx.client, device, selected);
      onSelect(selected, new Set(apps.apps.map((a) => a.packageName)));
    } catch (err) {
      apps = null;
      say((err as Error).message || "The profile's apps could not be read.", "error");
      onSelect(selected, null);
    }
  }

  function render(): void {
    strip.replaceChildren();
    for (const p of profiles) {
      const b = el("button", p.id === selected ? "pf-profile active" : "pf-profile");
      b.type = "button";
      b.setAttribute("role", "tab");
      b.setAttribute("aria-selected", String(p.id === selected));
      b.dataset.profile = p.id;
      const dot = el("span", "pf-dot", p.emoji ?? "");
      const color = cssColor(p.color);
      if (color) dot.style.background = color;
      b.append(dot, el("span", undefined, p.label));
      if (p.current) b.append(chip("In front", "success"));
      else if (!p.ready) b.append(chip("Needs setup", "warning"));
      b.addEventListener("click", async () => {
        if (p.id === selected) return;
        selected = p.id;
        managing = false;
        await loadApps();
        render();
      });
      strip.append(b);
    }
    const profile = profiles.find((p) => p.id === selected);
    actions.replaceChildren();
    if (profile && !profile.current) {
      const go = actionButton(`Switch the phone to ${profile.label}`, { variant: "primary", icon: "phone" });
      go.disabled = busy || !profile.ready;
      go.addEventListener("click", () => void switchTo(profile));
      actions.append(go);
    } else if (profile) {
      actions.append(el("span", "muted", `${profile.label} is in front on the phone.`));
    }
    if (profile && profile.id !== MAIN_PROFILE && profile.ready) {
      const manage = actionButton(managing ? "Done managing apps" : `Manage ${profile.label}'s apps`, { variant: "ghost", icon: "apps" });
      manage.addEventListener("click", () => {
        managing = !managing;
        render();
      });
      actions.append(manage);
    }
    renderManager(profile);
  }

  function renderManager(profile: PhoneProfile | undefined): void {
    if (!managing || !profile || !apps || !device) {
      manager.replaceChildren();
      manager.hidden = true;
      return;
    }
    manager.hidden = false;
    const phone = device;
    const installed = el("ul", "pf-apps");
    for (const app of apps.apps) {
      const li = el("li", "pf-app");
      const remove = actionButton("Remove", { variant: "ghost" });
      remove.disabled = busy;
      remove.addEventListener("click", () => void change(phone, profile, app.packageName, app.label, "remove"));
      li.append(el("span", undefined, app.label), el("span", "muted", app.packageName), remove);
      installed.append(li);
    }
    if (!apps.apps.length) installed.append(el("li", "muted", "No apps yet besides Cyclone."));
    const add = el("select", "cc-input");
    add.setAttribute("aria-label", "App to add");
    for (const app of apps.available) {
      const o = el("option", undefined, app.label);
      o.value = app.packageName;
      add.append(o);
    }
    if (apps.available[0]) add.value = apps.available[0].packageName;
    const addButton = actionButton("Add to profile", { variant: "primary" });
    addButton.disabled = busy || !apps.available.length;
    addButton.addEventListener("click", () => {
      const pkg = String(add.value ?? "");
      const app = apps?.available.find((a) => a.packageName === pkg);
      if (app) void change(phone, profile, app.packageName, app.label, "install");
    });
    const row = el("div", "cc-actions");
    row.append(add, addButton);
    setChildren(manager,
      el("h4", "pf-title", `Apps in ${profile.label}`), installed,
      el("h4", "pf-title", "Add an app Profile A has"), apps.available.length ? row : el("p", "muted", "Every app of Profile A is already here."),
      el("p", "cc-hint", "Adding copies the app Profile A already has into this profile (no store, no download). Removing takes it out of this profile only; its data here goes with it."));
  }

  async function switchTo(profile: PhoneProfile): Promise<void> {
    if (!device) return;
    busy = true;
    render();
    say(`Switching the phone to ${profile.label}…`);
    try {
      await profilesApi.switchTo(ctx.client, device, profile.id);
      say(`${profile.label} is now in front on the phone.`);
    } catch (err) {
      say((err as Error).message || "The phone could not switch.", "error");
    }
    busy = false;
    await load(device);
  }

  async function change(phone: string, profile: PhoneProfile, packageName: string, label: string, action: "install" | "remove"): Promise<void> {
    if (action === "remove" && typeof globalThis.confirm === "function" && !globalThis.confirm(`Remove ${label} from ${profile.label}? It stays in Profile A.`)) return;
    busy = true;
    render();
    try {
      await profilesApi.change(ctx.client, phone, profile.id, packageName, action);
      say(action === "install" ? `Added ${label} to ${profile.label}.` : `Removed ${label} from ${profile.label}.`);
    } catch (err) {
      say((err as Error).message || "The phone could not change the profile's apps.", "error");
    }
    busy = false;
    await loadApps();
    render();
  }

  element.hidden = true;
  return {
    element,
    load,
    destroy() {
      destroyed = true;
    },
  };
}
