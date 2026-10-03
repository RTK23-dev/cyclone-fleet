import "./styles.css";
import "./scan.css";
import "./tasks.css";
import "./connections.css";
import "./redesign.css";
import "./connections-redesign.css";
import "./chatgpt-attach.css";
import "./camera-streaming.css";
import { CyclonePcCompanionApp } from "./app.js";
import { createDesktopService } from "./services/serviceFactory.js";
import { mountCameraStreamingSettings } from "./ui/cameraStreamingSettings.js";
import { mountTransportOnboarding } from "./ui/transportOnboarding.js";

async function bootstrap(): Promise<void> {
  const root = document.getElementById("app");
  if (!root) throw new Error("Cyclone root element is missing");
  try {
    const service = await createDesktopService();
    const app = new CyclonePcCompanionApp(root, service);
    await app.start();
    const unmountTransportOnboarding = mountTransportOnboarding(service);
    const unmountCameraStreamingSettings = mountCameraStreamingSettings(service);
    window.addEventListener("beforeunload", () => {
      unmountCameraStreamingSettings();
      unmountTransportOnboarding();
      app.destroy();
    }, { once: true });
  } catch {
    root.innerHTML = `<main style="display:grid;place-items:center;min-height:100vh;background:#0b0b0e;color:#f7f7f8;font-family:Inter,ui-sans-serif,system-ui"><section style="max-width:520px;padding:32px;text-align:center"><h1>Cyclone couldn't start</h1><p style="color:#9a9ba3;line-height:1.6">Close and reopen Cyclone One. If the problem continues, open Settings after restarting and run diagnostics.</p></section></main>`;
  }
}

void bootstrap();
