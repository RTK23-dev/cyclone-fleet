mod chatgpt_attach;
mod chatgpt_share;
mod mcp_tunnel;
mod live_phone;
mod live_phone_bridge;

use rand::{rngs::OsRng, RngCore};
use serde::Serialize;
use std::net::TcpListener;
#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
use std::process::Command;
use tauri::{Manager, State};
use tauri_plugin_shell::ShellExt;

struct GatewayState {
    token: String,
    http_base: String,
    ws_base: String,
}

#[derive(Serialize)]
struct GatewaySession {
    token: String,
    http_base: String,
    ws_base: String,
}

#[tauri::command]
fn gateway_session(state: State<'_, GatewayState>) -> GatewaySession {
    GatewaySession {
        token: state.token.clone(),
        http_base: state.http_base.clone(),
        ws_base: state.ws_base.clone(),
    }
}

#[tauri::command]
fn diagnostics_folder(app: tauri::AppHandle) -> Result<String, String> {
    let path = app
        .path()
        .app_local_data_dir()
        .map_err(|error| error.to_string())?
        .join("runtime")
        .join("diagnostics");
    std::fs::create_dir_all(&path).map_err(|error| error.to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[tauri::command]
fn open_diagnostics_folder(app: tauri::AppHandle) -> Result<String, String> {
    let path = app
        .path()
        .app_local_data_dir()
        .map_err(|error| error.to_string())?
        .join("runtime")
        .join("diagnostics");
    std::fs::create_dir_all(&path).map_err(|error| error.to_string())?;

    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        Command::new("explorer.exe")
            .arg(&path)
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map_err(|error| error.to_string())?;
    }

    Ok(path.to_string_lossy().to_string())
}

/// Returns true for the exact launch link shape the local gateway mints: `/glass/#code=<token_urlsafe>`.
fn is_glass_launch_path(path: &str) -> bool {
    path.strip_prefix("/glass/#code=")
        .map(|code| {
            (16..=128).contains(&code.len())
                && code
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
        .unwrap_or(false)
}

/// Open Cyclone Glass (the local browser dashboard) in the default browser. Only a one-time
/// launch link on this app's own loopback gateway is accepted; nothing else can be opened.
#[tauri::command]
fn open_glass(state: State<'_, GatewayState>, path: String) -> Result<String, String> {
    if !is_glass_launch_path(&path) {
        return Err("Not a Cyclone Glass launch link.".to_string());
    }
    if !state.http_base.starts_with("http://127.0.0.1:") {
        return Err("Cyclone Glass opens on this PC only.".to_string());
    }
    let base = state.http_base.trim_end_matches('/');
    let url = format!("{base}{path}");

    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        // url.dll keeps the #fragment that carries the one-time code; explorer.exe may drop it.
        Command::new("rundll32.exe")
            .arg("url.dll,FileProtocolHandler")
            .arg(&url)
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map_err(|error| error.to_string())?;
    }
    #[cfg(not(windows))]
    let _ = url;

    Ok(format!("{base}/glass/"))
}

#[tauri::command]
async fn connector_status(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    sidecar_json(app, &["status", "--probe-gateway"]).await
}

#[tauri::command]
async fn local_ai_status(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    sidecar_json(app, &["status", "--probe-gateway"]).await
}

#[tauri::command]
async fn local_ai_adapters(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    sidecar_json(app, &["adapters"]).await
}

async fn sidecar_json(app: tauri::AppHandle, args: &[&str]) -> Result<serde_json::Value, String> {
    let output = app
        .shell()
        .sidecar("CycloneAgentMCP")
        .map_err(|error| error.to_string())?
        .args(args)
        .output()
        .await
        .map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err("Cyclone Agent connector status is unavailable".into());
    }
    serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())
}

#[tauri::command]
fn legacy_companion_warning() -> Option<String> {
    #[cfg(windows)]
    {
        let local = std::env::var_os("LOCALAPPDATA")?;
        let path = std::path::PathBuf::from(local).join("Cyclone PC Companion");
        if path.is_dir() {
            Some(
                "Cyclone PC Companion 3.8.x is installed beside Cyclone One. Prefer Cyclone One; uninstall the legacy companion so MCP points at the right runtime."
                    .into(),
            )
        } else {
            None
        }
    }
    #[cfg(not(windows))]
    {
        None
    }
}

#[tauri::command]
async fn connector_action(
    app: tauri::AppHandle,
    connector_id: String,
    action: String,
) -> Result<serde_json::Value, String> {
    let host = match connector_id.as_str() {
        "codex" => "codex",
        "grok" => "grok",
        "cursor" => "cursor",
        "opencode" | "deepseek-mcp" => "opencode",
        "copilot" => "copilot",
        "generic" | "generic-mcp" => "generic",
        _ => return Err("Unknown Cyclone connector".into()),
    };
    if action == "install" && host != "generic" {
        return Err("Install the selected AI harness first, then connect it to Cyclone".into());
    }
    let mut command = app
        .shell()
        .sidecar("CycloneAgentMCP")
        .map_err(|error| error.to_string())?;
    if host == "generic" {
        command = command.args(["copy-config", "generic"]);
    } else {
        command = command.args(["connect", host, "--verify"]);
    }
    let output = command.output().await.map_err(|error| error.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    if host == "generic" {
        return Ok(serde_json::json!({
            "ok": true,
            "message": String::from_utf8_lossy(&output.stdout).trim()
        }));
    }
    serde_json::from_slice(&output.stdout).map_err(|error| error.to_string())
}

fn strong_token() -> String {
    let mut bytes = [0_u8; 32];
    OsRng.fill_bytes(&mut bytes);
    bytes.iter().map(|value| format!("{value:02x}")).collect()
}

fn reserve_loopback_port() -> Result<u16, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|error| error.to_string())?;
    let port = listener
        .local_addr()
        .map_err(|error| error.to_string())?
        .port();
    drop(listener);
    Ok(port)
}

/// Remove only superseded developer-era Cyclone gateway monitors and executables.
///
/// The monitor must be stopped first because it otherwise respawns the visible console process.
/// Selection is restricted to PowerShell processes whose command line names the exact legacy
/// `monitor-pc-console.ps1` script, followed by three fixed gateway image names.
#[cfg(windows)]
fn cleanup_legacy_gateway_processes() {
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const STOP_LEGACY_MONITOR: &str = r#"
$selfPid = $PID
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object {
    $_.ProcessId -ne $selfPid -and
    $_.Name -in @('powershell.exe', 'pwsh.exe') -and
    $_.CommandLine -match '(?i)(?:^|[\\/\s])monitor-pc-console\.ps1(?:[\"\s]|$)'
  } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
"#;
    const LEGACY_IMAGES: [&str; 3] = [
        "cyclone-device-gateway.exe",
        "Cyclone Device Gateway.exe",
        "CycloneDeviceGateway.exe",
    ];
    let _ = Command::new("powershell.exe")
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-Command",
            STOP_LEGACY_MONITOR,
        ])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
    for image in LEGACY_IMAGES {
        let _ = Command::new("taskkill")
            .args(["/F", "/T", "/IM", image])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
    }
}

#[cfg(not(windows))]
fn cleanup_legacy_gateway_processes() {}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    cleanup_legacy_gateway_processes();
    let _ = live_phone::live_phone_control("stop".into());

    let token = strong_token();
    let gateway_port =
        reserve_loopback_port().expect("Cyclone could not reserve a local Gateway port");
    let http_base = format!("http://127.0.0.1:{gateway_port}");
    let ws_base = format!("ws://127.0.0.1:{gateway_port}");

    let runtime_token = token.clone();
    let runtime_http_base = http_base.clone();
    let runtime_port = gateway_port.to_string();
    let parent_pid = std::process::id().to_string();

    tauri::Builder::default()
        .manage(std::sync::Arc::new(live_phone_bridge::BridgeState::default()))
        .manage(std::sync::Arc::new(chatgpt_share::ShareState::default()))
        .manage(GatewayState {
            token,
            http_base,
            ws_base,
        })
        .plugin(tauri_plugin_shell::init())
        .setup(move |app| {
            let runtime_dir = app.path().app_local_data_dir()?.join("runtime");
            std::fs::create_dir_all(&runtime_dir)?;
            let handle = app.handle().clone();
            std::thread::spawn(move || {
                loop {
                    let command = match handle.shell().sidecar("CyclonePCRuntime") {
                        Ok(command) => command,
                        Err(_) => break,
                    };
                    let command = command.arg("serve")
                        .env("CYCLONE_DEVICE_GATEWAY_TOKEN", &runtime_token)
                        .env("CYCLONE_DEVICE_GATEWAY_URL", &runtime_http_base)
                        .env("CYCLONE_DEVICE_GATEWAY_PORT", &runtime_port)
                        .env("CYCLONE_DEVICE_GATEWAY_RUNTIME", runtime_dir.to_string_lossy().to_string())
                        .env("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "1")
                        .env("CYCLONE_PC_PARENT_PID", &parent_pid);
                    if let Ok((mut events, _child)) = command.spawn() {
                        // Drain output, then restart the owned runtime at the same private endpoint.
                        tauri::async_runtime::block_on(async move {
                            while let Some(event) = events.recv().await {
                                if matches!(event, tauri_plugin_shell::process::CommandEvent::Terminated(_)) { break; }
                            }
                        });
                    }
                    std::thread::sleep(std::time::Duration::from_secs(2));
                }
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            gateway_session,
            live_phone::live_phone_status,
            live_phone::live_phone_control,
            live_phone_bridge::live_bridge_connect,
            live_phone_bridge::live_bridge_status,
            live_phone_bridge::live_bridge_disconnect,
            live_phone_bridge::live_bridge_token,
            diagnostics_folder,
            open_diagnostics_folder,
            open_glass,
            connector_status,
            local_ai_status,
            local_ai_adapters,
            connector_action,
            legacy_companion_warning,
            mcp_tunnel::mcp_tunnel_status,
            mcp_tunnel::mcp_tunnel_start,
            mcp_tunnel::mcp_tunnel_stop,
            mcp_tunnel::mcp_tunnel_restart,
            mcp_tunnel::mcp_tunnel_rotate_token,
            mcp_tunnel::mcp_tunnel_set_mode,
            mcp_tunnel::mcp_tunnel_token,
            mcp_tunnel::mcp_tunnel_smoke,
            mcp_tunnel::mcp_tunnel_open_docs,
            chatgpt_attach::chatgpt_attach_load,
            chatgpt_attach::chatgpt_attach_save,
            chatgpt_attach::chatgpt_attach_sync,
            chatgpt_attach::chatgpt_attach_copy,
            chatgpt_attach::chatgpt_attach_save_handoff,
            chatgpt_attach::chatgpt_attach_resources,
            chatgpt_share::chatgpt_attach_share_status,
            chatgpt_share::chatgpt_attach_share_start,
            chatgpt_share::chatgpt_attach_share_stop
        ])
        .build(tauri::generate_context!())
        .expect("error while building Cyclone One")
        .run(|app, event| {
            if matches!(event, tauri::RunEvent::Exit) {
                let _ = live_phone::live_phone_control("stop".into());
                live_phone_bridge::shutdown(app.state::<std::sync::Arc<live_phone_bridge::BridgeState>>().inner());
                chatgpt_share::shutdown(app.state::<std::sync::Arc<chatgpt_share::ShareState>>().inner());
            }
        });
}

#[cfg(test)]
mod glass_launch_tests {
    use super::is_glass_launch_path;

    #[test]
    fn accepts_only_one_time_glass_links() {
        assert!(is_glass_launch_path(
            "/glass/#code=1JDKTd6EKYcslLhje1bJ_uQKi-Bn2CxK"
        ));
        assert!(!is_glass_launch_path("/glass/#code=short"));
        assert!(!is_glass_launch_path("/glass/#code=abcdefghijklmnop&x=1"));
        assert!(!is_glass_launch_path(
            "https://evil.example/glass/#code=abcdefghijklmnopqr"
        ));
        assert!(!is_glass_launch_path("/v1/fleet"));
        assert!(!is_glass_launch_path(
            "/glass/#code=abcdefghijklmnop\" & calc"
        ));
    }
}
