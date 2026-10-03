#!/usr/bin/env python3
"""One-time source integration for the 2.9.3 diagnostic Teach card.

The V292 compatibility shell was removed in the 4.4.8 Apple UI cleanup. This helper is a
historical one-off and is a no-op when that file is absent.
"""
from pathlib import Path

path = Path("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/CycloneMobileV292App.kt")
if not path.is_file():
    raise SystemExit(0)
text = path.read_text(encoding="utf-8")

if "com.cyclone.mobile.debug.PageDebugSandboxV293" not in text:
    anchor = "import com.cyclone.mobile.guided.TeachingGestureEvidenceV292\n"
    if anchor not in text:
        raise SystemExit("Could not locate V292 import anchor")
    text = text.replace(anchor, anchor + "import com.cyclone.mobile.debug.PageDebugSandboxV293\n", 1)

text = text.replace('Text("Cyclone 2.9.2", style = MaterialTheme.typography.labelSmall', 'Text("Cyclone 2.9.3", style = MaterialTheme.typography.labelSmall', 1)
text = text.replace(
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "2.9.2 turns what you demonstrate into semantic pages, directional gestures, Brain evidence and a reviewable routine instead of leaving it as a passive recording.")',
    'V292Hero(Icons.Rounded.School, "Teach Cyclone", "2.9.3 adds a Page Awareness Sandbox so you can freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.")',
    1,
)

marker = 'Text("Page Awareness Sandbox", fontWeight = FontWeight.Bold)'
if marker not in text:
    anchor = '        item { OutlinedButton(onClick = { RoutineTeachingRuntime.launchReport(context, null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(5.dp)); Text("Teaching history, AI notes & corrections") } }\n'
    if anchor not in text:
        raise SystemExit("Could not locate Teach history anchor")
    card = '''        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Page Awareness Sandbox", fontWeight = FontWeight.Bold)
                            Text("Freeze what Android sees, what PageContext keeps, what the Page Agent actually receives, and A/B-test the harness without executing actions.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = {
                        val service = CycloneAccessibilityService.instance
                        if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                        else {
                            PageDebugSandboxV293.start(service)
                            Toast.makeText(context, "PAGE DEBUG overlay started — capture the target app pages", Toast.LENGTH_SHORT).show()
                            (context as? Activity)?.moveTaskToBack(true)
                        }
                    }, enabled = !follow.active, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(5.dp)); Text("Start page sandbox")
                    }
                    OutlinedButton(onClick = { PageDebugSandboxV293.launchReport(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Info, null); Spacer(Modifier.width(5.dp)); Text("Open sandbox inspector")
                    }
                    Text("Model A/B probes are opt-in, use the selected OpenRouter model, make five calls on one frozen page, and never execute their proposed actions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
'''
    text = text.replace(anchor, card + anchor, 1)

path.write_text(text, encoding="utf-8")

# Keep the diagnostic inspector source compile-safe across PR merge checkouts. The first 2.9.3
# implementation used a trailing lambda while `weight` was the final smallButton parameter, so Kotlin
# interpreted the lambda as a Float argument. Reorder the callback to be last and provide the weighted
# LinearLayout helper used by the inspector rows.
sandbox_path = Path("apps/mobile/app/src/main/java/com/cyclone/mobile/debug/PageDebugSandboxV293.kt")
sandbox = sandbox_path.read_text(encoding="utf-8")
old_signature = 'private fun smallButton(label: String, onClick: () -> Unit, weight: Float? = null): Button = Button(this).apply {'
new_signature = 'private fun smallButton(label: String, weight: Float? = null, onClick: () -> Unit): Button = Button(this).apply {'
if old_signature in sandbox:
    sandbox = sandbox.replace(old_signature, new_signature, 1)

extension_marker = '    private fun LinearLayout.addView(view: View, weight: Float) {'
if extension_marker not in sandbox:
    anchor = '    private fun smallButton(label: String, weight: Float? = null, onClick: () -> Unit): Button = Button(this).apply {'
    if anchor not in sandbox:
        raise SystemExit("Could not locate PageDebugSandboxV293 smallButton helper")
    extension = '''    private fun LinearLayout.addView(view: View, weight: Float) {
        addView(view, LinearLayout.LayoutParams(0, dp(42), weight).apply { marginEnd = dp(5) })
    }

'''
    sandbox = sandbox.replace(anchor, extension + anchor, 1)

sandbox_path.write_text(sandbox, encoding="utf-8")
print("Cyclone 2.9.3 Teach sandbox integration is present and compile-safe")
