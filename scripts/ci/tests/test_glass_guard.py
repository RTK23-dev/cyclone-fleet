import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "glass_guard.py"
SPEC = importlib.util.spec_from_file_location("glass_guard", SCRIPT)
glass_guard = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(glass_guard)


class GlassGuardTest(unittest.TestCase):
    def tree(self, files: dict[str, str], deps: dict[str, str] | None = None) -> Path:
        root = Path(tempfile.mkdtemp())
        (root / "src").mkdir()
        (root / "package.json").write_text(json.dumps({"name": "g", "dependencies": deps or {}}), encoding="utf-8")
        for name, text in files.items():
            (root / "src" / name).write_text(text, encoding="utf-8")
        return root

    def test_current_glass_is_clean(self):
        self.assertEqual([], glass_guard.scan())

    def test_intelligence_and_unsafe_habits_are_rejected(self):
        root = self.tree(
            {
                "a.ts": 'fetch("https://openrouter.ai/api/v1/chat/completions")',
                "b.ts": 'localStorage.setItem("t", token)',
                "c.ts": 'import { invoke } from "@tauri-apps/api/core";',
                "d.ts": "node.innerHTML = html;",
                "e.ts": "const apiKey = read();",
            },
            deps={"react": "^19"},
        )
        errors = "\n".join(glass_guard.scan(root))
        for label in ("model or provider call", "localStorage", "desktop runtime", "innerHTML/eval", "API key handling", "runtime dependencies"):
            self.assertIn(label, errors)

    def test_device_model_names_are_not_mistaken_for_models(self):
        root = self.tree({
            "a.ts": 'const model = record.model; // Pixel 8\nsessionStorage.getItem("x");',
            "b.ts": '/** Never write to localStorage. */\nconst DENY = ["password", "api_key"];',
        })
        self.assertEqual([], glass_guard.scan(root))


if __name__ == "__main__":
    unittest.main()
