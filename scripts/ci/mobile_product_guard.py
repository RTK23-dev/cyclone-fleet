#!/usr/bin/env python3
"""Fail-fast guard for Cyclone Mobile's production product surfaces."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32App.kt"
FEATURES = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32FeaturePages.kt"
SETTINGS_426 = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneSettings426.kt"
AI_CHAT = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt"
INTELLIGENCE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneIntelligenceControls.kt"
OVERLAY_COMPOSER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt"
TASK_PRESENTATION = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/TaskPresentationSnapshot.kt"
TASK_PANEL = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneAskTaskPanel.kt"
MINIMIZED_COMPOSER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneMinimizedComposerBar.kt"
GLASS_COMPOSER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/InAppGlass.kt"
BRAIN_V39 = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39BrainPage.kt"
MANIFEST = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
MAIN = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"
CAMERA_VIEWER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/stream/CameraStreamViewerActivity.kt"

REQUIRED_APP = (
    "V32Destination.HOME -> V32HomePage",
    "V32Destination.PROFILES -> CycloneProfilesPage",
    "V32Destination.AI -> V39AiChatPage",
    "V32Destination.ROUTINES -> CycloneRoutinesPage",
    "V32Destination.BRAIN -> CycloneV39BrainPage",
    "CycloneSettingsPage426(context, refreshTick,",
)
REQUIRED_FEATURES = (
    "internal fun V32TeachPage",
    "FollowMeLearnerRuntime.start(context)",
    '"Start Follow Me"',
)
FORBIDDEN_FEATURES = (
    "internal fun V32AiPage",
    "internal fun V32BrainPage",
    "internal fun V32SettingsPage",
    "GatewayAiCard",
    "CycloneAlpineBackdrop",
)
REQUIRED_SETTINGS_426 = (
    "internal fun CycloneSettingsPage426",
    'Settings426Row("Model & API"',
    'Settings426Row("User notes"',
    'Settings426Row("Phone control"',
    'Settings426Row("Profile engine"',
    'Settings426Row("PC Gateway"',
    "CycloneModelPill",
    "CycloneAiAccessProfileStore.write",
    "CyclonePermissionSetup.phoneControlSnapshot",
    "BackgroundSetup.read",
)
REQUIRED_AI_CHAT = (
    "internal fun V39AiChatPage",
    "CycloneTextChat.answer(context",
    "RequestIntentRouter.route(",
    "WorkspaceTasks.queueRequest(normalized)",
    "OpenRouterCatalogStore.activeId(context)",
    '"Ask Cyclone…"',
    # R3: the model selector is the header pill; its sheet drops from the header.
    "AskHeader(",
    "CycloneConversationBubble(",
    "AskModelSheet(",
    "onChanged = ::persistAiControls",
    "onPhotos = { openPhotos() }",
    'filesLabel = "Files"',
)
REQUIRED_INTELLIGENCE = (
    "private enum class OverlaySettingsStep { MODEL, INTELLIGENCE }",
    'OverlaySettingsStep.MODEL -> "Model"',
    'OverlaySettingsStep.INTELLIGENCE -> "Intelligence"',
)
FORBIDDEN_INTELLIGENCE = (
    "OverlaySettingsStep.AUTONOMY",
    '-> "Autonomy"',
)
REQUIRED_OVERLAY_COMPOSER = (
    'OverlayAppleToolTile(Icons.Rounded.PhotoLibrary, "Photos"',
    'OverlayAppleToolTile(Icons.Rounded.CameraAlt, "Camera"',
    'OverlayAppleMenuRow(Icons.Rounded.AttachFile, "Files"',
    'OverlayAppleMenuRow(Icons.Rounded.Tune, "Model & intelligence"',
    "CycloneSignatureGlass(",
)
FORBIDDEN_OVERLAY_COMPOSER = (
    '"Files & photos"',
    'modelLabel',
    'intelligenceLabel',
)
REQUIRED_TASK_PRESENTATION = (
    "data class TaskPresentationSnapshot(",
    "object TaskPresentationProjector",
    "object TaskMilestoneProjector",
    "object TaskFollowUpPolicy",
    "plannedMilestones",
    "progressFraction",
)
REQUIRED_TASK_PANEL = (
    "TaskPresentationProjector.project(projectedTask)",
    "CycloneConversationTokens.taskRadius",
    "TaskFollowUpAction.RUN_AGAIN",
    "TaskFollowUpAction.TRY_AGAIN",
    "TaskFollowUpAction.OPEN_APP",
    "CycloneSignatureCard(",
)
# Plan 27: the folded composer is the glass Ask bar and stays a real composer: type, add, voice, send, open.
REQUIRED_MINIMIZED_COMPOSER = (
    "GlassComposerBar(",
    'fieldDescription = "Ask Cyclone minimized composer"',
    "onAdd = onAdd",
    "onVoice = onVoice",
    "onSend = onSubmit",
    "onExpand = onExpand",
)
REQUIRED_GLASS_COMPOSER = (
    "BasicTextField(",
    "SignatureGlyph.ADD",
    "SignatureGlyph.MIC",
    "SignatureGlyph.SEND",
    "ImeAction.Send",
)
REQUIRED_BRAIN_V39 = (
    "internal fun CycloneV39BrainPage",
    'CycloneSectionTitle("Recent outcomes")',
    "TaskResultActivityV292",
    '"View details"',
)
REQUIRED_MANIFEST = (
    'android:name=".MainActivity"',
    'android:name=".gateway.GatewaySettingsActivity"',
    'android:name=".stream.CameraStreamViewerActivity"',
    'android:name=".CycloneAccessibilityService"',
    'android:name=".CycloneNotificationListener"',
)
REQUIRED_MAIN = (
    "CycloneMobileV32App()",
    "AutomationRuntime.initialize(this)",
    "AppLearnerRuntime.initialize(this)",
    "CycloneBrainRuntime.initialize(this)",
)
REQUIRED_CAMERA_VIEWER = (
    'EXTRA_STREAM_TOKEN = "cyclone_stream_token"',
    'EXTRA_STREAM_TARGET = "cyclone_stream_target"',
    'VIEWER_TOKEN_HEADER = "X-Cyclone-Viewer-Token"',
    'VIEWER_TARGET_HEADER = "X-Cyclone-Viewer-Target"',
    ".addHeader(VIEWER_TOKEN_HEADER, streamToken)",
    ".addHeader(VIEWER_TARGET_HEADER, streamTarget)",
    "uri.query != null",
    "fitNativeAspect(",
)


def missing_tokens(text: str, required: tuple[str, ...]) -> list[str]:
    return [token for token in required if token not in text]


def check() -> list[str]:
    errors: list[str] = []
    # glass: Cyclone Glass, the local browser dashboard (Cyclone V5 plan/03-glass-v1.md).
    supported_apps = {"mobile", "device-gateway", "pc-companion", "glass"}
    for path in (ROOT / "apps").iterdir():
        if path.is_dir() and path.name not in supported_apps:
            errors.append(f"Unsupported product component: apps/{path.name}")
    for path, required in (
        (APP, REQUIRED_APP),
        (FEATURES, REQUIRED_FEATURES),
        (SETTINGS_426, REQUIRED_SETTINGS_426),
        (AI_CHAT, REQUIRED_AI_CHAT),
        (INTELLIGENCE, REQUIRED_INTELLIGENCE),
        (OVERLAY_COMPOSER, REQUIRED_OVERLAY_COMPOSER),
        (TASK_PRESENTATION, REQUIRED_TASK_PRESENTATION),
        (TASK_PANEL, REQUIRED_TASK_PANEL),
        (MINIMIZED_COMPOSER, REQUIRED_MINIMIZED_COMPOSER),
        (GLASS_COMPOSER, REQUIRED_GLASS_COMPOSER),
        (BRAIN_V39, REQUIRED_BRAIN_V39),
        (MANIFEST, REQUIRED_MANIFEST),
        (MAIN, REQUIRED_MAIN),
        (CAMERA_VIEWER, REQUIRED_CAMERA_VIEWER),
    ):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"{path.relative_to(ROOT)} unreadable: {error}")
            continue
        for token in missing_tokens(text, required):
            errors.append(f"{path.relative_to(ROOT)} missing invariant: {token}")
        if path == FEATURES:
            for retired in FORBIDDEN_FEATURES:
                if retired in text:
                    errors.append(f"{path.relative_to(ROOT)} still ships retired UI: {retired}")
        if path == AI_CHAT and "CycloneAlpineBackdrop" in text:
            errors.append(f"{path.relative_to(ROOT)} still uses the alpine weather backdrop")
        if path == INTELLIGENCE:
            for retired in FORBIDDEN_INTELLIGENCE:
                if retired in text:
                    errors.append(f"{path.relative_to(ROOT)} still exposes retired quick control: {retired}")
        if path == OVERLAY_COMPOSER:
            for retired in FORBIDDEN_OVERLAY_COMPOSER:
                if retired in text:
                    errors.append(f"{path.relative_to(ROOT)} still merges attachment actions: {retired}")
        if path in (APP, FEATURES, SETTINGS_426, AI_CHAT, MAIN):
            for retired in ("Teamwork Sniper", "TEAMWORK_SNIPER_PACKAGE", "coreWsUrl", "coreToken", "BridgeClient.start"):
                if retired in text:
                    errors.append(f"{path.relative_to(ROOT)} exposes retired integration: {retired}")
        if path == CAMERA_VIEWER and ("?token=" in text or "getQueryParameter(\"token\")" in text):
            errors.append("Camera viewer must never carry viewer credentials in its WebSocket URL")
    retired_paths = (
        ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneAlpineBackdrop.kt",
        ROOT / "apps/mobile/app/src/main/res/drawable-nodpi/cyclone_alpine_day.jpg",
        ROOT / "apps/mobile/app/src/main/res/drawable-nodpi/cyclone_alpine_night.jpg",
        ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/GatewayAiCard.kt",
        ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v31/CycloneV31Cards.kt",
        ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/modules/CycloneModulesScreen.kt",
        ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/SetupComposeCompat.kt",
    )
    for path in retired_paths:
        if path.exists():
            errors.append(f"retired UI still present: {path.relative_to(ROOT)}")
    return errors


def main() -> int:
    errors = check()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(
        "Cyclone mobile product invariants preserved: Home, Profiles, Ask Cyclone, Routines, "
        "Brain, utility Settings, PC Gateway, native-aspect camera viewer, accessibility and notification services"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
