import {
  GLASS_SECRET_PHONE_COPY,
  GLASS_SECRET_PRIVACY_COPY,
  needsSecretWaitTitle,
} from "../core/fleet.js";
import { el } from "./dom.js";

export type SecretPresence = "missing" | "set" | "waiting";

export interface SecretsCardModel {
  slotLabel: string;
  presence?: SecretPresence;
}

/**
 * Path 1 wait banner: secret is typed on the phone overlay.
 * No password field. No capture buttons. No secret value rendering.
 */
export function createSecretsCard(model: SecretsCardModel): HTMLElement {
  const presence = model.presence ?? "waiting";
  const card = el("article", "secrets-card secrets-card-waiting");
  card.dataset.state = "needs-secret";
  card.dataset.presence = presence;
  card.setAttribute("role", "status");
  card.append(
    el("div", "secrets-card-kicker", "WAITING ON PHONE"),
    el("h2", "secrets-card-title", needsSecretWaitTitle(model.slotLabel)),
    el("p", "secrets-card-copy", "Type it on the phone overlay. Glass will not take a password here."),
    el("p", "secrets-card-privacy", GLASS_SECRET_PRIVACY_COPY),
    el("p", "secrets-card-phone", GLASS_SECRET_PHONE_COPY),
  );
  const presenceRow = el("div", "secrets-card-presence");
  presenceRow.append(
    el("span", "secrets-card-slot", model.slotLabel),
    el("span", `secrets-card-state secrets-card-state-${presence}`, presenceCopy(presence)),
  );
  card.append(presenceRow);
  return card;
}

function presenceCopy(presence: SecretPresence): string {
  if (presence === "set") return "Set";
  if (presence === "missing") return "Missing";
  return "Waiting";
}
