from __future__ import annotations

from typing import Any, Literal

from mcp.server import MCPServer
from mcp.types import ToolAnnotations

from .tools import PhoneTools

SERVER_NAME = "cyclone-phone"
SERVER_VERSION = "1.0.0-beta.2"

INSTRUCTIONS = (
    "Use Cyclone directly whenever the user asks to inspect or control a connected phone. Start with phone_list, then use the selected phone's typed tools. "
    "If exactly one phone is READY it may be selected automatically; if more than one is READY, pass device_id explicitly and never guess. "
    "session_id is required on observe/act/locate/search/inspect/screenshot/skill_run/group_act. "
    "Read session_id from phone_status (sessions inventory when present). "
    "Pass session_id=default-foreground for the live human display (display 0). Named workspace sessions require display_id > 0 and must never be rewritten onto display 0. "
    "Use phone_workspace on default-foreground display 0, then pass workspaceId+workspaceGeneration on mutating phone_act. Layer 2 is not a VD session. "
    "phone.open_app uses params.package only (Android package id, e.g. com.android.chrome). Do not send an app display name or packageName. "
    "Browse/open Chrome is phone_act tool=phone.open_app params.package=com.android.chrome. It does not use OpenRouter. "
    "Do not invent default-foreground when session_id is missing. "
    "Observe before mutations, re-observe afterward, verify meaningful changes, and use screenshots only when structured evidence is insufficient. "
    "If Companion owns input, yield in Cyclone or retry with request_ai_control=true; a locked phone is never stolen. "
    "Virtual lifecycle and routine tools accept only explicit Cyclone identifiers and fail closed when the authenticated Gateway route is unavailable. "
    "This server has no shell, PowerShell, arbitrary ADB, root, su, subprocess or script-evaluation tool."
)

READ = ToolAnnotations(read_only_hint=True, idempotent_hint=True, open_world_hint=True)
WRITE = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=False, open_world_hint=True)


def build_server(phone_tools: PhoneTools | None = None) -> MCPServer:
    tools = phone_tools or PhoneTools()
    mcp = MCPServer(SERVER_NAME, instructions=INSTRUCTIONS)

    @mcp.tool(annotations=READ)
    def phone_list() -> dict[str, Any]:
        """List Cyclone devices and safe readiness metadata. No device selection is performed."""
        return tools.call("phone_list", {})

    @mcp.tool(annotations=READ)
    def phone_status(device_id: str | None = None) -> dict[str, Any]:
        """Read readiness for one phone. device_id may be omitted only when exactly one READY device exists."""
        return tools.call("phone_status", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_capabilities(device_id: str | None = None, refresh: bool = False) -> dict[str, Any]:
        """Read typed Cyclone capability metadata for one phone."""
        return tools.call("phone_capabilities", {"device_id": device_id, "refresh": refresh})

    @mcp.tool(annotations=READ)
    def phone_observe(
        session_id: str,
        device_id: str | None = None,
        mode: Literal["compact", "full"] = "compact",
        include_screenshot: bool = False,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Observe one phone. Compact semantic state is the default. session_id is required (default-foreground for the live human display)."""
        return tools.call("phone_observe", {
            "device_id": device_id, "mode": mode, "include_screenshot": include_screenshot,
            "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=READ)
    def phone_locate(
        goal: str,
        session_id: str,
        device_id: str | None = None,
        query: str | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Fuse readiness, a bounded Page Card (pageText + pageSummary), and goal-ranked candidates. session_id is required."""
        return tools.call("phone_locate", {
            "device_id": device_id, "goal": goal, "query": query,
            "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=READ)
    def phone_ui_search(
        query: str,
        session_id: str,
        device_id: str | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Search the semantic/raw UI index for one phone. session_id is required."""
        return tools.call("phone_ui_search", {
            "device_id": device_id, "query": query, "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=READ)
    def phone_inspect_element(
        element_id: str,
        session_id: str,
        device_id: str | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Inspect one observation-scoped UI element candidate. session_id is required."""
        return tools.call("phone_inspect_element", {
            "device_id": device_id, "element_id": element_id,
            "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=READ)
    def phone_screenshot(
        session_id: str,
        device_id: str | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Capture screenshot evidence for one phone; use only after structured evidence is insufficient. session_id is required."""
        return tools.call("phone_screenshot", {
            "device_id": device_id, "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=READ)
    def phone_current_page(device_id: str | None = None) -> dict[str, Any]:
        """Read the current semantic page record for one phone."""
        return tools.call("phone_current_page", {"device_id": device_id})

    @mcp.tool(annotations=READ)
    def phone_page_history(device_id: str | None = None) -> dict[str, Any]:
        """Read bounded page/action transition history for one phone."""
        return tools.call("phone_page_history", {"device_id": device_id})

    @mcp.tool(annotations=WRITE)
    def phone_workspace(
        operation: Literal["list", "register", "switch", "pause", "release", "arm", "next"],
        session_id: str,
        params: dict[str, Any] | None = None,
        device_id: str | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Layer 2 workspaces on default-foreground display 0. After switch/next, pass workspaceId+workspaceGeneration on mutating phone_act.params. Not a VD session."""
        return tools.call("phone_workspace", {
            "operation": operation, "params": params or {},
            "device_id": device_id, "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=WRITE)
    def phone_act(
        tool: Literal["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"],
        params: dict[str, Any],
        goal: str,
        session_id: str,
        device_id: str | None = None,
        user_authorized: bool = False,
        display_id: int | None = None,
        request_ai_control: bool = False,
    ) -> dict[str, Any]:
        """Forward one typed action to Cyclone. session_id is required. phone.open_app requires params.package (Android package id, e.g. com.android.chrome), not an app display name or packageName. Browse/open Chrome does not use OpenRouter. After a Layer 2 switch, mutating params MUST include workspaceId+workspaceGeneration. request_ai_control yields Companion input and never steals a locked phone. There is no generic command/shell/ADB escape hatch."""
        return tools.call("phone_act", {
            "device_id": device_id, "tool": tool, "params": params, "goal": goal,
            "user_authorized": user_authorized, "session_id": session_id, "display_id": display_id,
            "request_ai_control": request_ai_control,
        })

    @mcp.tool(annotations=WRITE)
    def phone_skill_save(
        goal: str,
        steps: list[dict[str, Any]],
        device_id: str | None = None,
        pageKey: str = "",
        app: str = "",
        params: dict[str, Any] | None = None,
        session_id: str | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Compile 2+ verified steps into a disabled AutomationStore draft. Unverified steps do not write. session_id is optional and forwarded when present."""
        return tools.call("phone_skill_save", {
            "device_id": device_id, "goal": goal, "steps": steps, "pageKey": pageKey, "app": app, "params": params or {},
            "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=WRITE)
    def phone_skill_run(
        skill_id: str,
        session_id: str,
        device_id: str | None = None,
        dryRun: bool = False,
        params: dict[str, Any] | None = None,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Run a verified skill live, or dry-run a draft without mutation. session_id is required. Draft match never skipModel."""
        return tools.call("phone_skill_run", {
            "device_id": device_id, "skill_id": skill_id, "dryRun": dryRun, "params": params or {},
            "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=WRITE)
    def phone_group_act(
        device_ids: list[str],
        tool: Literal["phone.click", "phone.long_press", "phone.swipe", "phone.scroll", "phone.back", "phone.home", "phone.open_app", "phone.wait_for"],
        params: dict[str, Any],
        goal: str,
        session_id: str,
        display_id: int | None = None,
    ) -> dict[str, Any]:
        """Run one typed, non-secret phone action on 1..32 explicitly selected devices and return per-device evidence. session_id is required."""
        return tools.call("phone_group_act", {
            "device_ids": device_ids, "tool": tool, "params": params, "goal": goal,
            "session_id": session_id, "display_id": display_id,
        })

    @mcp.tool(annotations=READ)
    def phone_debug_bundle(device_id: str | None = None, goal: str = "", expected: str = "") -> dict[str, Any]:
        """Capture bounded diagnostic evidence when perception, execution and verification disagree."""
        return tools.call("phone_debug_bundle", {"device_id": device_id, "goal": goal, "expected": expected})

    @mcp.tool(annotations=WRITE)
    def phone_teach_start(device_id: str | None = None, goal: str = "") -> dict[str, Any]:
        """Start Cyclone's canonical Teach/Follow Me session for one phone."""
        return tools.call("phone_teach_start", {"device_id": device_id, "goal": goal})

    @mcp.tool(annotations=READ)
    def phone_teach_status(device_id: str | None = None) -> dict[str, Any]:
        """Read the current teaching session for one phone."""
        return tools.call("phone_teach_status", {"device_id": device_id})

    @mcp.tool(annotations=WRITE)
    def phone_teach_stop(device_id: str | None = None, compile_for_review: bool = True) -> dict[str, Any]:
        """Stop Cyclone teaching and optionally compile a disabled-for-review routine."""
        return tools.call("phone_teach_stop", {"device_id": device_id, "compile_for_review": compile_for_review})

    @mcp.tool(annotations=READ)
    def phone_virtual_list() -> dict[str, Any]:
        """List Cyclone-managed virtual phone instances."""
        return tools.call("phone_virtual_list", {})

    @mcp.tool(annotations=WRITE)
    def phone_virtual_create(provider: str, image: str) -> dict[str, Any]:
        """Create one virtual phone from an installed provider image."""
        return tools.call("phone_virtual_create", {"provider": provider, "image": image})

    @mcp.tool(annotations=WRITE)
    def phone_virtual_start(instance_id: str) -> dict[str, Any]:
        """Start one explicitly identified Cyclone virtual phone."""
        return tools.call("phone_virtual_start", {"instance_id": instance_id})

    @mcp.tool(annotations=WRITE)
    def phone_virtual_stop(instance_id: str) -> dict[str, Any]:
        """Stop one explicitly identified Cyclone virtual phone."""
        return tools.call("phone_virtual_stop", {"instance_id": instance_id})

    @mcp.tool(annotations=WRITE)
    def phone_routine_run(device_id: str, routine_id: str) -> dict[str, Any]:
        """Run one known routine on one explicitly selected Cyclone device."""
        return tools.call("phone_routine_run", {"device_id": device_id, "routine_id": routine_id})

    @mcp.tool(annotations=READ)
    def phone_routine_status(device_id: str, run_id: str) -> dict[str, Any]:
        """Read one explicitly targeted Cyclone routine run."""
        return tools.call("phone_routine_status", {"device_id": device_id, "run_id": run_id})

    @mcp.tool(annotations=WRITE)
    def phone_routine_cancel(device_id: str, run_id: str) -> dict[str, Any]:
        """Cancel one explicitly targeted Cyclone routine run."""
        return tools.call("phone_routine_cancel", {"device_id": device_id, "run_id": run_id})

    @mcp.tool(annotations=READ)
    def phone_app_manual(device_id: str, app: str, query: str | None = None) -> dict[str, Any]:
        """Read an app's manual from a paired phone: what you can do in the app (abilities), the path to each in the app's
        own words, how to find one item in its lists, the self-quiz and the manual as Markdown. With query, the abilities
        that fit it first. Read only: it never taps anything and holds no content (no chats, names or messages)."""
        args: dict[str, Any] = {"device_id": device_id, "app": app}
        if query:
            args["query"] = query
        return tools.call("phone_app_manual", args)

    @mcp.tool(annotations=READ)
    def phone_lab_missions() -> dict[str, Any]:
        """List Cyclone Lab missions (goal, suite, how success is read from the phone) for measuring Cyclone."""
        return tools.call("phone_lab_missions", {})

    @mcp.tool(annotations=WRITE)
    def phone_lab_start(device_id: str, name: str, missions: list[str], variants: list[dict[str, Any]] | None = None,
                        repetitions: int = 1) -> dict[str, Any]:
        """Run a Cyclone Lab experiment: missions x variants x repetitions on one paired phone, scored from the phone.
        A variant is {name, modelId?, effort?, workingMinutes?, marks?, freshMemory?, promptAddendum?, useMap?}. The lab never
        approves consequential actions and never supplies secrets."""
        return tools.call("phone_lab_start", {"device_id": device_id, "name": name, "missions": missions,
                                              "variants": variants or [{"name": "A"}], "repetitions": repetitions})

    @mcp.tool(annotations=READ)
    def phone_lab_report(experiment_id: str | None = None) -> dict[str, Any]:
        """Cyclone Lab results: without an id the experiment list; with one, success rates with confidence intervals,
        A/B comparisons, insights and every failed run with its cause, checks and tool errors."""
        return tools.call("phone_lab_report", {"experiment_id": experiment_id} if experiment_id else {})

    @mcp.tool(annotations=WRITE)
    def phone_lab_stop(experiment_id: str) -> dict[str, Any]:
        """Stop a running Cyclone Lab experiment after the current mission is stopped."""
        return tools.call("phone_lab_stop", {"experiment_id": experiment_id})

    return mcp


def run_stdio() -> None:
    build_server().run(transport="stdio")
