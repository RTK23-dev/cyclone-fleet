//! Settings control plane for the ChatGPT / Grok chat MCP HTTPS tunnel.
//!
//! Spawns the bundled PowerShell pack under `%LOCALAPPDATA%\Cyclone One\mcp-tunnel\`.
//! Never logs the full bearer token. Does not touch `~/.grok/config.toml`.

use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
#[cfg(windows)]
use std::process::{Command, Stdio};
use std::time::Duration;
use tauri::{AppHandle, Manager};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

const BUNDLE_VERSION: &str = "1.1.0-mcp-tunnel.1";
const LOCAL_MCP_URL: &str = "http://127.0.0.1:8787/mcp";
const LOCAL_HEALTH_URL: &str = "http://127.0.0.1:8787/health";

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TunnelToken {
    token: String,
    last4: String,
}

pub fn install_dir() -> Result<PathBuf, String> {
    let local = std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set")?;
    Ok(PathBuf::from(local).join("Cyclone One").join("mcp-tunnel"))
}

fn bundled_pack_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let resource = app
        .path()
        .resource_dir()
        .map_err(|error| error.to_string())?;
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources").join("mcp-tunnel");
    let candidates = [
        resource.join("resources").join("mcp-tunnel"),
        resource.join("mcp-tunnel"),
        manifest,
    ];
    for candidate in candidates {
        if candidate.join("gateway").join("server.js").is_file() {
            return Ok(candidate);
        }
    }
    Err("bundled MCP tunnel pack is missing from this Cyclone One build".into())
}

fn copy_dir_skip_secrets(src: &Path, dest: &Path) -> io::Result<()> {
    fs::create_dir_all(dest)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        if name_str.eq_ignore_ascii_case("gateway.env")
            || name_str.eq_ignore_ascii_case("artifacts")
            || name_str.eq_ignore_ascii_case("runtime")
        {
            continue;
        }
        let from = entry.path();
        let to = dest.join(&name);
        if from.is_dir() {
            copy_dir_skip_secrets(&from, &to)?;
        } else {
            if let Some(parent) = to.parent() {
                fs::create_dir_all(parent)?;
            }
            fs::copy(&from, &to)?;
        }
    }
    Ok(())
}

fn ensure_installed(app: &AppHandle) -> Result<PathBuf, String> {
    let dest = install_dir()?;
    let src = bundled_pack_dir(app)?;
    copy_dir_skip_secrets(&src, &dest).map_err(|error| {
        format!("could not install MCP tunnel pack to {}: {error}", dest.display())
    })?;
    fs::create_dir_all(dest.join("artifacts").join("runtime")).map_err(|error| error.to_string())?;
    fs::create_dir_all(dest.join("config")).map_err(|error| error.to_string())?;
    fs::write(dest.join("BUNDLE_VERSION"), BUNDLE_VERSION).map_err(|error| error.to_string())?;
    Ok(dest)
}

fn resolve_mcp_binary(app: &AppHandle) -> Option<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("CycloneAgentMCP.exe"));
            candidates.push(dir.join("CycloneAgentMCP-x86_64-pc-windows-msvc.exe"));
        }
    }
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let local = PathBuf::from(local);
        candidates.push(local.join("Cyclone One").join("CycloneAgentMCP.exe"));
        candidates.push(local.join("Cyclone PC Companion").join("CycloneAgentMCP.exe"));
    }
    if let Ok(resource) = app.path().resource_dir() {
        candidates.push(resource.join("CycloneAgentMCP.exe"));
    }
    candidates.into_iter().find(|path| path.is_file())
}

fn load_dot_env(path: &Path) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let Ok(text) = fs::read_to_string(path) else {
        return out;
    };
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        out.insert(key.trim().to_string(), value.trim().to_string());
    }
    out
}

fn env_file(root: &Path) -> PathBuf {
    root.join("config").join("gateway.env")
}

fn token_last4(token: &str) -> Option<String> {
    if token.len() < 4 {
        None
    } else {
        Some(token[token.len() - 4..].to_string())
    }
}

fn redact(text: &str, token: Option<&str>) -> String {
    let mut out = text.to_string();
    if let Some(token) = token {
        if token.len() >= 16 {
            out = out.replace(token, "***");
        }
    }
    out
}

fn decode_output(bytes: &[u8]) -> String {
    if bytes.starts_with(&[0xFF, 0xFE]) {
        let words: Vec<u16> = bytes[2..]
            .chunks_exact(2)
            .map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]]))
            .collect();
        return String::from_utf16_lossy(&words);
    }
    if bytes.len() >= 4 && bytes[1] == 0 && bytes[0] != 0 && bytes[3] == 0 {
        let words: Vec<u16> = bytes
            .chunks_exact(2)
            .map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]]))
            .collect();
        return String::from_utf16_lossy(&words);
    }
    String::from_utf8_lossy(bytes).into_owned()
}

fn extract_json(stdout: &str) -> Result<Value, String> {
    let trimmed = stdout.trim();
    if trimmed.is_empty() {
        return Err("tunnel script produced no JSON".into());
    }
    if let Ok(value) = serde_json::from_str::<Value>(trimmed) {
        return Ok(value);
    }
    for line in trimmed.lines().rev() {
        let line = line.trim();
        if line.starts_with('{') {
            if let Ok(value) = serde_json::from_str::<Value>(line) {
                return Ok(value);
            }
        }
    }
    if let (Some(start), Some(end)) = (trimmed.find('{'), trimmed.rfind('}')) {
        if end > start {
            if let Ok(value) = serde_json::from_str::<Value>(&trimmed[start..=end]) {
                return Ok(value);
            }
        }
    }
    Err("tunnel script output was not JSON".into())
}

#[cfg(windows)]
fn run_script(
    root: &Path,
    script_name: &str,
    extra_args: &[&str],
    mcp_binary: Option<&Path>,
    timeout: Duration,
) -> Result<Value, String> {
    let script = root.join("scripts").join(script_name);
    if !script.is_file() {
        return Err(format!("missing {}", script.display()));
    }
    let mut args = vec![
        "-NoLogo".into(),
        "-NoProfile".into(),
        "-NonInteractive".into(),
        "-WindowStyle".into(),
        "Hidden".into(),
        "-ExecutionPolicy".into(),
        "Bypass".into(),
        "-File".into(),
        script.to_string_lossy().into_owned(),
        "-Json".into(),
    ];
    for arg in extra_args {
        args.push((*arg).into());
    }

    let mut command = Command::new("powershell.exe");
    command
        .args(&args)
        .current_dir(root)
        .env("CYCLONE_MCP_TUNNEL_ROOT", root)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW);
    if let Some(mcp) = mcp_binary {
        command.env("MCP_COMMAND", mcp);
    }

    let output = run_with_timeout(&mut command, timeout)?;
    let stdout = decode_output(&output.stdout);
    let stderr = decode_output(&output.stderr);
    let token = load_dot_env(&env_file(root))
        .get("GATEWAY_BEARER_TOKEN")
        .cloned();
    let stdout = redact(&stdout, token.as_deref());
    let stderr = redact(&stderr, token.as_deref());

    match extract_json(&stdout) {
        Ok(value) => Ok(value),
        Err(error) => {
            let detail = if stderr.trim().is_empty() {
                stdout.chars().take(400).collect::<String>()
            } else {
                stderr.chars().take(400).collect::<String>()
            };
            if output.status.success() {
                Err(format!("{error}: {detail}"))
            } else {
                Err(if detail.is_empty() {
                    format!("{script_name} failed")
                } else {
                    format!("{script_name} failed: {detail}")
                })
            }
        }
    }
}

#[cfg(windows)]
fn run_with_timeout(command: &mut Command, timeout: Duration) -> Result<std::process::Output, String> {
    let mut child = command.spawn().map_err(|error| error.to_string())?;
    let start = std::time::Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return child.wait_with_output().map_err(|error| error.to_string()),
            Ok(None) => {
                if start.elapsed() > timeout {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err("tunnel command timed out".into());
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(error) => return Err(error.to_string()),
        }
    }
}

#[cfg(not(windows))]
fn run_script(
    _root: &Path,
    _script_name: &str,
    _extra_args: &[&str],
    _mcp_binary: Option<&Path>,
    _timeout: Duration,
) -> Result<Value, String> {
    Err("Remote MCP tunnel is available on Windows Cyclone One".into())
}

fn stopped_status(root: &Path, message: &str) -> Value {
    json!({
        "ok": true,
        "state": "stopped",
        "mode": "readonly",
        "tokenLast4": Value::Null,
        "publicUrl": Value::Null,
        "mcpUrl": Value::Null,
        "healthUrl": Value::Null,
        "localMcpUrl": LOCAL_MCP_URL,
        "localHealthUrl": LOCAL_HEALTH_URL,
        "gatewayAlive": false,
        "cloudflaredAlive": false,
        "healthOk": false,
        "installPath": root.display().to_string(),
        "grokStdioUntouched": true,
        "message": message,
    })
}

fn with_defaults(mut value: Value, root: &Path) -> Value {
    let obj = value.as_object_mut();
    if let Some(obj) = obj {
        obj.entry("localMcpUrl".to_string())
            .or_insert_with(|| json!(LOCAL_MCP_URL));
        obj.entry("localHealthUrl".to_string())
            .or_insert_with(|| json!(LOCAL_HEALTH_URL));
        obj.entry("installPath".to_string())
            .or_insert_with(|| json!(root.display().to_string()));
        obj.entry("grokStdioUntouched".to_string())
            .or_insert(json!(true));
        obj.entry("ok".to_string()).or_insert(json!(true));
    }
    value
}

fn set_env_key(path: &Path, key: &str, value: &str) -> Result<(), String> {
    let mut lines: Vec<String> = if path.is_file() {
        fs::read_to_string(path)
            .map_err(|error| error.to_string())?
            .lines()
            .map(|line| line.to_string())
            .collect()
    } else {
        Vec::new()
    };
    let mut found = false;
    for line in lines.iter_mut() {
        if line.starts_with(&format!("{key}=")) {
            *line = format!("{key}={value}");
            found = true;
        }
    }
    if !found {
        if lines.is_empty() {
            lines.push("# Generated by Cyclone One Settings. Do not commit.".into());
            lines.push("GATEWAY_HOST=127.0.0.1".into());
            lines.push("GATEWAY_PORT=8787".into());
        }
        lines.push(format!("{key}={value}"));
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    fs::write(path, lines.join("\n") + "\n").map_err(|error| error.to_string())?;
    protect_env_file(path);
    Ok(())
}

#[cfg(windows)]
fn protect_env_file(path: &Path) {
    if let (Ok(domain), Ok(user)) = (std::env::var("USERDOMAIN"), std::env::var("USERNAME")) {
        let grant = format!("{domain}\\{user}:(R,W)");
        let _ = Command::new("icacls")
            .args([path.to_string_lossy().as_ref(), "/inheritance:r", "/grant:r", &grant])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
    }
}

#[cfg(not(windows))]
fn protect_env_file(_path: &Path) {}

#[tauri::command]
pub async fn mcp_tunnel_status(app: AppHandle) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        let mcp = resolve_mcp_binary(&app);
        run_script(
            &root,
            "status-tunnel.ps1",
            &[],
            mcp.as_deref(),
            Duration::from_secs(20),
        )
        .map(|value| with_defaults(value, &root))
        .or_else(|error| {
            if error.contains("missing") {
                Ok(stopped_status(&root, &error))
            } else {
                Err(error)
            }
        })
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn mcp_tunnel_start(app: AppHandle, mode: Option<String>) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        let mcp = resolve_mcp_binary(&app);
        let normalized = match mode.as_deref() {
            None | Some("") => None,
            Some("readonly") => Some("readonly"),
            Some("full") => Some("full"),
            Some(other) => return Err(format!("invalid tunnel mode '{other}'")),
        };
        let extra: Vec<&str> = if let Some(mode) = normalized {
            vec!["-Mode", mode]
        } else {
            Vec::new()
        };
        run_script(
            &root,
            "start-tunnel.ps1",
            &extra,
            mcp.as_deref(),
            Duration::from_secs(90),
        )
        .map(|value| with_defaults(value, &root))
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn mcp_tunnel_stop(app: AppHandle) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        run_script(&root, "stop-tunnel.ps1", &[], None, Duration::from_secs(30))
            .map(|value| with_defaults(value, &root))
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn mcp_tunnel_restart(app: AppHandle, mode: Option<String>) -> Result<Value, String> {
    let current_mode = mode.clone();
    mcp_tunnel_stop(app.clone()).await?;
    mcp_tunnel_start(app, current_mode).await
}

#[tauri::command]
pub async fn mcp_tunnel_rotate_token(app: AppHandle) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        let mcp = resolve_mcp_binary(&app);
        run_script(
            &root,
            "rotate-token.ps1",
            &[],
            mcp.as_deref(),
            Duration::from_secs(120),
        )
        .map(|value| with_defaults(value, &root))
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn mcp_tunnel_set_mode(app: AppHandle, mode: String) -> Result<Value, String> {
    if mode != "readonly" && mode != "full" {
        return Err("invalid tunnel mode".into());
    }
    tauri::async_runtime::spawn_blocking({
        let mode = mode.clone();
        let app = app.clone();
        move || -> Result<PathBuf, String> {
            let root = ensure_installed(&app)?;
            set_env_key(&env_file(&root), "GATEWAY_MODE", &mode)?;
            Ok(root)
        }
    })
    .await
    .map_err(|error| error.to_string())??;
    let status = mcp_tunnel_status(app.clone()).await?;
    let running = status
        .get("state")
        .and_then(Value::as_str)
        .map(|state| state == "running" || state == "degraded")
        .unwrap_or(false);
    if running {
        mcp_tunnel_restart(app, Some(mode)).await
    } else {
        Ok(status)
    }
}

#[tauri::command]
pub async fn mcp_tunnel_token(app: AppHandle) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        let values = load_dot_env(&env_file(&root));
        let token = values
            .get("GATEWAY_BEARER_TOKEN")
            .cloned()
            .filter(|value| value != "replace-me" && value.len() >= 16)
            .ok_or_else(|| "no bearer token yet — start the tunnel once".to_string())?;
        let last4 = token_last4(&token).unwrap_or_else(|| "????".into());
        serde_json::to_value(TunnelToken { token, last4 }).map_err(|error| error.to_string())
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn mcp_tunnel_smoke(app: AppHandle) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        run_script(
            &root,
            "smoke-gateway.ps1",
            &[],
            None,
            Duration::from_secs(60),
        )
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub async fn mcp_tunnel_open_docs(app: AppHandle) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let root = ensure_installed(&app)?;
        let docs = root.join("docs").join("CONNECTOR_SETUP.md");
        if !docs.is_file() {
            return Err("connector setup notes are missing from the tunnel pack".into());
        }
        #[cfg(windows)]
        {
            Command::new("cmd")
                .args(["/C", "start", "", docs.to_string_lossy().as_ref()])
                .creation_flags(CREATE_NO_WINDOW)
                .spawn()
                .map_err(|error| error.to_string())?;
        }
        Ok(docs.to_string_lossy().into_owned())
    })
    .await
    .map_err(|error| error.to_string())?
}

#[cfg(test)]
mod tests {
    use super::{extract_json, redact, token_last4};

    #[test]
    fn last4_hides_the_rest() {
        assert_eq!(token_last4("abcdefghijklmnopqrstuvwxyz").as_deref(), Some("wxyz"));
        assert_eq!(token_last4("abc"), None);
    }

    #[test]
    fn redact_strips_full_token() {
        let token = "super-secret-token-value-1234";
        assert_eq!(redact(&format!("Authorization: Bearer {token}"), Some(token)), "Authorization: Bearer ***");
        assert!(redact("status running last4=1234", Some(token)).contains("1234"));
    }

    #[test]
    fn extract_json_uses_last_object() {
        let raw = "starting\n{\"ok\":true,\"state\":\"running\"}\n";
        let value = extract_json(raw).unwrap();
        assert_eq!(value["state"], "running");
    }

    #[test]
    fn decode_utf16_le_powershell_output() {
        let json = "{\"ok\":true}";
        let mut bytes = vec![0xFF, 0xFE];
        for unit in json.encode_utf16() {
            bytes.extend_from_slice(&unit.to_le_bytes());
        }
        assert_eq!(super::decode_output(&bytes), json);
    }
}
