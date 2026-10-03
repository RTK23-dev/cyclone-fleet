# Cyclone One — first run

Cyclone One 1.5.5 is self-contained for Android transport onboarding, native camera streaming and ChatGPT Attach.

1. Open Cyclone One and click **Connect phone**.
2. No Android Studio or separate ADB install is required. Cyclone One ships bundled Android Platform-Tools 37.0.1.
3. Choose one transport:
   - **USB:** enable Android Developer options → USB debugging, connect the cable, unlock the phone, approve this PC, then click **Check USB**.
   - **Wireless:** Android Settings → Developer options → Wireless debugging → Pair device with pairing code. Enter the pairing address, the separate device address, and the 6-digit code, then click **Pair & connect**.
   - **VMOS:** use Android 15 when available. Android 13/14 are compatible; Android 12 and older are unsupported for this Cyclone setup. Install Cyclone Mobile 4.4.2 inside VMOS and enable provider ADB. In **ChatGPT Attach → Add pad**, paste the VMOS **Connect command**, paste its **Connect Key** separately, then click **Sync fleet**. The Connect Key stays on this PC.
4. Cyclone One starts its private One gateway automatically on loopback. VMOS Sync opens the SSH tunnel, connects the bundled ADB client and rescans the Gateway inventory.
5. Transport is not Cyclone trust. After ADB is ready, open **Cyclone Mobile → PC Gateway & QR pairing** and finish the normal QR/manual trust-pairing flow in Cyclone One. Re-run **Sync fleet** until **Cloud AI session** is green.
6. For **Camera streaming**, open **Settings → Streaming camera**, choose one source phone and up to five receiving Cyclone phones, then start the stream. Cyclone preserves the source camera sensor aspect ratio end to end; receiving phones contain the native frame instead of cropping or stretching it to 16:9 or 9:16. Receiving phones must run Cyclone Mobile 4.4.2 or newer.
7. **ChatGPT Attach** exports only pads with a real Cloud Control session. It will not copy a handoff containing a local/fake session token. SSH Connect Keys and VMOS AccessKeys never enter the handoff.
8. Click **Share to ChatGPT** so Custom GPT Actions get an HTTPS CONTROL_API. ChatGPT cannot reach localhost. In the GPT Action editor choose **Authentication: None**; the Cyclone OpenAPI schema requires `X-Cyclone-Session-Token`, which the GPT fills from the latest pasted `SESSION_TOKEN`.

The One gateway remains loopback-only; do not expose its bearer token or ADB endpoint publicly. Camera viewers receive per-session loopback credentials and no public listening socket is opened for camera streaming. Share to ChatGPT publishes only the Cloud Control surface. The short-lived `SESSION_TOKEN` is the only control credential given to ChatGPT, and it remains scoped to its Cyclone device/session.
