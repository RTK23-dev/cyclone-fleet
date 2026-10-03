import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { manualFileName, parseManual, quizLine } from "../.test-dist/services/manual.js";
import { abilitiesView, tryItGoal } from "../.test-dist/ui/abilitiesView.js";

const ABILITY = (over) => ({
  id: "ab:0123456789ab", kind: "switch", name: "Requests on Messages", place: "screen:list:0123456789abcdef", placeName: "Messages",
  path: ["Home", "Messages", "Requests"], tap: "Requests", pick: null, effect: "switch", setId: "set:requests", provenance: "walked",
  confidence: 0.91, note: null, say: ["Requests", "see Requests", "open Requests"], ...over,
});
const MANUAL = {
  placeId: "package:com.instagram.android", appLabel: "Instagram", currentVersion: "402.0",
  abilities: [
    ABILITY({}),
    ABILITY({ id: "ab:ffffffffffff", kind: "offer", name: "Camera (in Add photos and files)", place: "screen:unknown:fedcba98765abcde",
      placeName: "Add photos and files", path: ["Home", "Add photos and files", "Camera"], tap: null, pick: "Camera", effect: "choose",
      setId: null, provenance: "mapped", confidence: 0.75 }),
    ABILITY({ id: "not-an-id" }),
    ABILITY({ id: "ab:aaaaaaaaaaaa", effect: "pay" }),
  ],
  truncated: false, query: "see message requests", hits: [{ id: "ab:0123456789ab", score: 0.97 }, { id: "ab:bbbbbbbbbbbb", score: 0.5 }], clear: true,
  quiz: { at: 5, asked: 3, answered: 2, goals: [
    { goal: "see message requests", abilityId: "ab:0123456789ab", score: 0.97 },
    { goal: "attach a photo", abilityId: "ab:ffffffffffff", score: 0.8 },
    { goal: "order a pizza", abilityId: null, score: 0 },
  ] },
  scores: { map: 0.75, dictionary: 1, quiz: 0.67, walks: null, places: 4, named: 3, panels: 1, lists: 1, ordered: 1, abilities: 2, walkedAbilities: 1 },
  markdown: "# Instagram (com.instagram.android) · 402.0 · look only\n## Abilities\n- Requests on Messages → Home › Messages › Requests",
};

test("the manual parser keeps only well-formed abilities, known hits and bounded scores", () => {
  const view = parseManual(MANUAL);
  assert.deepEqual(view.abilities.map((a) => a.id), ["ab:0123456789ab", "ab:ffffffffffff"]);
  assert.deepEqual(view.hits, [{ id: "ab:0123456789ab", score: 0.97 }]);
  assert.equal(view.scores.walks, null);
  assert.equal(quizLine(view.quiz), "Answers 2 of 3 goals · 1 to explore");
  assert.equal(manualFileName(view), "Instagram manual.md");
  assert.equal(parseManual({ ...MANUAL, scores: { ...MANUAL.scores, map: 7 } }).scores.map, null);
});

test("the Abilities tab shows hits first, the quiz with Map deeper, Try it and Export", () => {
  installMiniDom();
  const view = parseManual(MANUAL);
  const calls = [];
  const root = abilitiesView(view, "see message requests", {
    search: (q) => calls.push(["search", q]),
    mapDeeper: () => calls.push(["deeper"]),
    tryIt: (a) => calls.push(["try", a.id]),
    exportManual: () => calls.push(["export"]),
  });
  assert.match(root.textContent, /Answers 2 of 3 goals · 1 to explore/);
  assert.match(root.querySelector(".quiz-gaps").textContent, /order a pizza/);
  const hits = root.querySelector(".ability-hits");
  assert.match(hits.textContent, /Requests on Messages/);
  assert.match(hits.textContent, /Clear match/);
  assert.match(hits.textContent, /Home›Messages›Requests|Home › Messages › Requests|HomeMessagesRequests/);
  const buttons = root.querySelectorAll("button");
  buttons.find((b) => b.textContent.includes("Map deeper")).click();
  buttons.find((b) => b.textContent.includes("Export manual")).click();
  hits.querySelectorAll("button").find((b) => b.textContent.includes("Try it")).click();
  assert.deepEqual(calls, [["deeper"], ["export"], ["try", "ab:0123456789ab"]]);
  const offer = view.abilities[1];
  assert.equal(tryItGoal(offer, "Instagram"),
    "In Instagram, go to: Camera (in Add photos and files) (Home › Add photos and files › Camera). Stop before choosing “Camera”. Do not type, send, pay, delete or change anything.");
  const empty = abilitiesView(parseManual({ ...MANUAL, abilities: [], hits: [] }), "", { search() {}, mapDeeper() {}, tryIt() {}, exportManual() {} });
  assert.match(empty.textContent, /Map the app first/);
});
