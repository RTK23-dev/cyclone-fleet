//! One-click HTTPS share of Device Gateway Cloud Control for ChatGPT Actions.
//!
//! cloudflared fronts the private loopback gateway. CONTROL_API becomes
//! `https://<trycloudflare-host>/cloud`. Session tokens stay in the handoff;
//! SSH Connect Keys never enter this module's public JSON.

use serde_json::{json, Value};
use std::fs;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{AppHandle, Manager, State};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Default)]
struct ShareTunnel {
    child: Option<Child>,
    url: Option<String>,
    local_port: u16,
}

#[derive(Default)]
pub struct ShareState(Mutex<ShareTunnel>);

fn share_status_path() -> Result<PathBuf, String> {
    Ok(crate::chatgpt_attach::install_dir()?.join("share.json"))
}

fn write_share_status(url: &str, local_port: u16, running: bool) {
    let Ok(path) = share_status_path() else { return };
    let _ = fs::create_dir_all(path.parent().unwrap_or(&path));
    let payload = json!({
        "url": url,
        "localPort": local_port,
        "running": running,
        "localBase": format!("http://127.0.0.1:{local_port}/cloud"),
    });
    let _ = fs::write(path, payload.to_string());
}

fn cloudflared_exe(app: &AppHandle) -> Result<PathBuf, String> {
    let resource = app.path().resource_dir().map_err(|error| error.to_string())?;
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources").join("live-phone").join("cloudflared.exe");
    let candidates = [
        resource.join("resources").join("live-phone").join("cloudflared.exe"),
        resource.join("live-phone").join("cloudflared.exe"),
        manifest,
    ];
    candidates
        .into_iter()
        .find(|path| path.is_file())
        .ok_or_else(|| "Share to ChatGPT needs the bundled cloudflared.exe. Reinstall Cyclone One.".into())
}

fn public_cloud_url(host: &str) -> String {
    format!("https://{host}/cloud")
}

fn status_payload(running: bool, url: Option<&str>, local_port: u16, message: &str) -> Value {
    json!({
        "ok": running && url.is_some(),
        "running": running,
        "url": url.unwrap_or(""),
        "localBase": if local_port > 0 {
            format!("http://127.0.0.1:{local_port}/cloud")
        } else {
            String::new()
        },
        "message": message,
    })
}

pub fn shutdown(state: &Arc<ShareState>) {
    if let Ok(mut tunnel) = state.0.lock() {
        if let Some(mut child) = tunnel.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        tunnel.url = None;
        if tunnel.local_port > 0 {
            write_share_status("", tunnel.local_port, false);
        }
    }
}

#[tauri::command]
pub fn chatgpt_attach_share_status(state: State<'_, Arc<ShareState>>) -> Result<Value, String> {
    let mut tunnel = state.inner().0.lock().map_err(|_| "Share to ChatGPT is busy")?;
    let alive = if let Some(child) = tunnel.child.as_mut() {
        child.try_wait().map_err(|error| error.to_string())?.is_none()
    } else {
        false
    };
    if !alive {
        tunnel.child = None;
        tunnel.url = None;
        if tunnel.local_port > 0 {
            write_share_status("", tunnel.local_port, false);
        }
        return Ok(status_payload(false, None, tunnel.local_port, "Share to ChatGPT is stopped."));
    }
    Ok(status_payload(
        true,
        tunnel.url.as_deref(),
        tunnel.local_port,
        if tunnel.url.is_some() {
            "ChatGPT can reach Cloud Control over HTTPS."
        } else {
            "Share to ChatGPT is starting."
        },
    ))
}

#[tauri::command]
pub fn chatgpt_attach_share_stop(state: State<'_, Arc<ShareState>>) -> Result<Value, String> {
    shutdown(state.inner());
    Ok(status_payload(false, None, 0, "Share to ChatGPT stopped."))
}

#[tauri::command]
pub fn chatgpt_attach_share_start(
    app: AppHandle,
    state: State<'_, Arc<ShareState>>,
    local_port: u16,
) -> Result<Value, String> {
    #[cfg(not(windows))]
    {
        let _ = (app, state, local_port);
        return Err("Share to ChatGPT is available on Windows Cyclone One".into());
    }
    #[cfg(windows)]
    {
        let port = if local_port > 0 { local_port } else { 8765 };
        {
            let mut tunnel = state.inner().0.lock().map_err(|_| "Share to ChatGPT is busy")?;
            if let Some(child) = tunnel.child.as_mut() {
                if child.try_wait().map_err(|error| error.to_string())?.is_none()
                    && tunnel.local_port == port
                    && tunnel.url.is_some()
                {
                    let url = tunnel.url.clone();
                    return Ok(status_payload(true, url.as_deref(), port, "ChatGPT can reach Cloud Control over HTTPS."));
                }
            }
            if let Some(mut child) = tunnel.child.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
            tunnel.url = None;
            tunnel.local_port = port;

            let exe = cloudflared_exe(&app)?;
            let target = format!("http://127.0.0.1:{port}");
            let mut command = Command::new(exe);
            command
                .args(["tunnel", "--no-autoupdate", "--url", &target, "--protocol", "http2"])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::piped())
                .creation_flags(CREATE_NO_WINDOW);
            let mut child = command.spawn().map_err(|_| "Could not start the ChatGPT HTTPS share.".to_string())?;
            let stderr = child.stderr.take().ok_or("Could not read ChatGPT share status")?;
            let pid = child.id();
            tunnel.child = Some(child);
            let owned = Arc::clone(state.inner());
            std::thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    for word in line.split_whitespace() {
                        let value = word.trim_matches(|c: char| !(c.is_ascii_alphanumeric() || ":/.-".contains(c)));
                        if let Some(host) = value.strip_prefix("https://") {
                            if host.ends_with(".trycloudflare.com")
                                && host.len() < 200
                                && host.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '.')
                            {
                                if let Ok(mut tunnel) = owned.0.lock() {
                                    if tunnel.child.as_ref().map(|item| item.id()) == Some(pid) {
                                        let url = public_cloud_url(host);
                                        tunnel.url = Some(url.clone());
                                        write_share_status(&url, tunnel.local_port, true);
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }

        for _ in 0..120 {
            {
                let mut tunnel = state.inner().0.lock().map_err(|_| "Share to ChatGPT is busy")?;
                let alive = if let Some(child) = tunnel.child.as_mut() {
                    child.try_wait().map_err(|error| error.to_string())?.is_none()
                } else {
                    false
                };
                if !alive {
                    tunnel.child = None;
                    tunnel.url = None;
                    write_share_status("", port, false);
                    return Err("ChatGPT HTTPS share stopped before it became ready.".into());
                }
                if let Some(url) = tunnel.url.clone() {
                    return Ok(status_payload(true, Some(&url), port, "ChatGPT can reach Cloud Control over HTTPS."));
                }
            }
            std::thread::sleep(Duration::from_millis(250));
        }
        Err("ChatGPT HTTPS share is taking longer than expected. Click Share to ChatGPT again.".into())
    }
}
