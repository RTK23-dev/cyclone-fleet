//! One-owned HTTPS tunnel. Runtime restart never restarts this process or changes its URL.
use serde_json::{json, Value};
use std::{io::{BufRead, BufReader}, process::{Child, Command, Stdio}, sync::{Arc, Mutex}, time::Duration};
use tauri::Manager;
#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[derive(Default)]
struct Tunnel { child: Option<Child>, url: Option<String> }
impl Drop for Tunnel {
    fn drop(&mut self) { if let Some(child) = self.child.as_mut() { let _ = child.kill(); let _ = child.wait(); } }
}
#[derive(Default)]
pub struct BridgeState(Mutex<Tunnel>);

#[tauri::command]
pub fn live_bridge_status(state: tauri::State<'_, Arc<BridgeState>>) -> Result<Value, String> {
    let mut tunnel = state.inner().0.lock().map_err(|_| "Connection busy")?;
    let alive = if let Some(child) = tunnel.child.as_mut() { child.try_wait().map_err(|_| "Connection unavailable")?.is_none() } else { false };
    if !alive { tunnel.url = None; tunnel.child = None; }
    Ok(json!({"running": alive, "url": tunnel.url, "transport": "Direct Live Phone MCP"}))
}

#[tauri::command]
pub fn live_bridge_connect(app: tauri::AppHandle, state: tauri::State<'_, Arc<BridgeState>>) -> Result<Value, String> {
    {
        let mut tunnel = state.inner().0.lock().map_err(|_| "Connection busy")?;
        if let Some(child) = tunnel.child.as_mut() {
            if child.try_wait().map_err(|_| "Connection unavailable")?.is_none() {
                if tunnel.url.is_some() { return Ok(json!({"running":true,"url":tunnel.url})); }
            }
        }
        if tunnel.child.is_none() {
            // Bundled pinned binary only. No PATH lookup, shell, script, or user command.
            let resource = app.path().resource_dir().map_err(|_| "Install Cyclone One first")?;
            let exe = resource.join("resources").join("live-phone").join("cloudflared.exe");
            if !exe.is_file() { return Err("Direct bridge component missing. Reinstall Cyclone One.".into()); }
            let mut command = Command::new(exe);
            command.args(["tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:8788", "--protocol", "http2"])
                .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
            #[cfg(windows)]
            command.creation_flags(0x08000000);
            let mut child = command.spawn().map_err(|_| "Could not start secure connection")?;
            let stderr = child.stderr.take().ok_or("Could not read connection status")?;
            let pid = child.id();
            tunnel.child = Some(child);
            tunnel.url = None;
            let owned = Arc::clone(state.inner());
            std::thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    for word in line.split_whitespace() {
                        let value = word.trim_matches(|c: char| !(c.is_ascii_alphanumeric() || ":/.-".contains(c)));
                        if let Some(host) = value.strip_prefix("https://") {
                            if host.ends_with(".trycloudflare.com") && host.len() < 200 && host.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '.') {
                                if let Ok(mut tunnel) = owned.0.lock() { if tunnel.child.as_ref().map(|c| c.id()) == Some(pid) { tunnel.url = Some(format!("https://{host}/mcp")); } }
                            }
                        }
                    }
                }
            });
        }
    }

    // The 1.3 UX is intentionally one step: clicking Yes must return a complete
    // handoff, not force the user to wait and then copy three separate values.
    // Wait briefly for Cloudflare to publish the ephemeral HTTPS hostname.
    for _ in 0..120 {
        {
            let mut tunnel = state.inner().0.lock().map_err(|_| "Connection busy")?;
            let alive = if let Some(child) = tunnel.child.as_mut() {
                child.try_wait().map_err(|_| "Connection unavailable")?.is_none()
            } else { false };
            if !alive {
                tunnel.url = None;
                tunnel.child = None;
                return Err("Secure cloud connection stopped before it became ready.".into());
            }
            if let Some(url) = tunnel.url.clone() {
                return Ok(json!({"running":true,"url":url,"transport":"Direct Live Phone MCP"}));
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    Err("Secure cloud connection is taking longer than expected. Press Yes again to finish the handoff.".into())
}

#[tauri::command]
pub fn live_bridge_disconnect(state: tauri::State<'_, Arc<BridgeState>>) -> Result<Value, String> {
    crate::live_phone::live_phone_control("stop".into())?;
    let mut tunnel = state.inner().0.lock().map_err(|_| "Connection busy")?;
    if let Some(mut child) = tunnel.child.take() { let _ = child.kill(); let _ = child.wait(); }
    tunnel.url = None;
    Ok(json!({"running":false}))
}

#[cfg(windows)]
fn unprotect(bytes: &mut [u8]) -> Result<Vec<u8>, String> {
    #[repr(C)] struct Blob { size: u32, data: *mut u8 }
    #[link(name="crypt32")]
    extern "system" { fn CryptUnprotectData(input: *mut Blob, description: *mut *mut u16, entropy: *mut Blob, reserved: *mut std::ffi::c_void, prompt: *mut std::ffi::c_void, flags: u32, output: *mut Blob) -> i32; }
    #[link(name="kernel32")]
    extern "system" { fn LocalFree(memory: *mut std::ffi::c_void) -> *mut std::ffi::c_void; }
    let mut input = Blob { size: bytes.len() as u32, data: bytes.as_mut_ptr() };
    let mut output = Blob { size: 0, data: std::ptr::null_mut() };
    unsafe {
        if CryptUnprotectData(&mut input, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), 1, &mut output) == 0 { return Err("Could not unlock connector credential".into()); }
        let value = std::slice::from_raw_parts(output.data, output.size as usize).to_vec();
        LocalFree(output.data.cast());
        Ok(value)
    }
}
#[tauri::command]
pub fn live_bridge_token() -> Result<Value, String> {
    #[cfg(windows)] {
        let local = std::env::var_os("LOCALAPPDATA").ok_or("Windows required")?;
        let path = std::path::PathBuf::from(local).join("Cyclone One").join("live-phone").join("cloud-key.dpapi");
        let mut bytes = std::fs::read(path).map_err(|_| "Wait for Cyclone runtime to start")?;
        let token = String::from_utf8(unprotect(&mut bytes)?).map_err(|_| "Invalid connector credential")?;
        Ok(json!({"token":token}))
    }
    #[cfg(not(windows))] { Err("Windows required".into()) }
}

pub fn shutdown(state: &Arc<BridgeState>) { if let Ok(mut tunnel) = state.0.lock() { if let Some(mut child) = tunnel.child.take() { let _ = child.kill(); let _ = child.wait(); } tunnel.url = None; } }
