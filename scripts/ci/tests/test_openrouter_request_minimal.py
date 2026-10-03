import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
AI = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ai"
class OpenRouterMinimalRequestTest(unittest.TestCase):
    def test_all_request_paths_use_the_shared_compatibility_boundary(self):
        for name in ["OpenRouterAdaptiveAgent.kt", "OpenRouterQuickAgent.kt", "MissionLearningConsolidatorV292.kt", "model/ModelQualificationRunner.kt"]:
            source = (AI / name).read_text()
            self.assertIn("PortableModelRequest.body(", source, name)
            self.assertNotIn('.put("temperature"', source, name)
            self.assertNotIn('.put("reasoning"', source, name)
            self.assertNotIn('.put("response_format"', source, name)
    def test_portable_contract_never_relaxes_privacy_or_substitutes_models(self):
        source = (AI / "model/PortableModelRequest.kt").read_text()
        self.assertIn('.put("model", modelId)', source)
        self.assertIn('.put("only", JSONArray(providers))', source)
        self.assertIn('profile?.allowProviderFallbacks ?: false', source)
        self.assertNotIn('data_collection', source)
        self.assertNotIn('reasoning_effort', source)
if __name__ == "__main__":
    unittest.main()
