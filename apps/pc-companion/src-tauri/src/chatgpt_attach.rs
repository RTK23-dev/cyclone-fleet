//! ChatGPT Attach: persist VMOS fleet secrets with DPAPI, sync ADB, export handoff.
//!
//! Connect Keys and VMOS AccessKeys never leave this module in logs or handoff files.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;
use tauri::{AppHandle, Manager};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PadConfig {
    id: String,
    label: String,
    #[serde(default)]
    ssh_host: String,
    #[serde(default = "default_ssh_port")]
    ssh_port: u16,
    #[serde(default = "default_ssh_user")]
    ssh_user: String,
    #[serde(default = "default_adb_port")]
    local_adb_port: u16,
    #[serde(default = "default_remote_adb")]
    remote_adb_spec: String,
    #[serde(default)]
    serial: Option<String>,
    #[serde(default)]
    connect_key: Option<String>,
    #[serde(default)]
    has_connect_key: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FleetConfig {
    #[serde(default)]
    control_api_base: String,
    #[serde(default)]
    default_goal: String,
    #[serde(default)]
    vmos_api_key: Option<String>,
    #[serde(default)]
    has_vmos_api_key: Option<bool>,
    #[serde(default)]
    pads: Vec<PadConfig>,
}

fn default_ssh_port() -> u16 { 1824 }
fn default_ssh_user() -> String { "s".into() }
fn default_adb_port() -> u16 { 63670 }
fn default_remote_adb() -> String { "localhost:1".into() }

pub fn install_dir() -> Result<PathBuf, String> {
    let local = std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set")?;
    Ok(PathBuf::from(local).join("Cyclone One").join("chatgpt-attach"))
}

fn config_path() -> Result<PathBuf, String> {
    Ok(install_dir()?.join("fleet.dpapi"))
}

fn bundled_dir(app: &AppHandle) -> Result<PathBuf, String> {
    let resource = app.path().resource_dir().map_err(|error| error.to_string())?;
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources").join("chatgpt-attach");
    let candidates = [
        resource.join("resources").join("chatgpt-attach"),
        resource.join("chatgpt-attach"),
        manifest,
    ];
    for candidate in candidates {
        if candidate.join("scripts").join("Sync-VmosFleet.ps1").is_file() {
            return Ok(candidate);
        }
    }
    Err("bundled ChatGPT Attach pack is missing from this Cyclone One build".into())
}

fn public_config(mut config: FleetConfig) -> FleetConfig {
    config.has_vmos_api_key = Some(config.vmos_api_key.as_ref().is_some_and(|value| !value.is_empty()));
    config.vmos_api_key = None;
    for pad in &mut config.pads {
        pad.has_connect_key = Some(pad.connect_key.as_ref().is_some_and(|value| !value.is_empty()) || pad.has_connect_key == Some(true));
        pad.connect_key = None;
    }
    config
}

fn merge_secrets(previous: Option<FleetConfig>, mut incoming: FleetConfig) -> FleetConfig {
    let previous_pads = previous.as_ref().map(|item| item.pads.clone()).unwrap_or_default();
    if incoming.vmos_api_key.as_ref().map(|value| value.is_empty()).unwrap_or(true) {
        incoming.vmos_api_key = previous.and_then(|item| item.vmos_api_key);
    }
    for pad in &mut incoming.pads {
        if pad.connect_key.as_ref().map(|value| value.is_empty()).unwrap_or(true) {
            if let Some(old) = previous_pads.iter().find(|item| item.id == pad.id) {
                pad.connect_key = old.connect_key.clone();
                pad.has_connect_key = Some(old.connect_key.as_ref().is_some_and(|value| !value.is_empty()));
            }
        } else {
            pad.has_connect_key = Some(true);
        }
    }
    incoming
}

#[cfg(windows)]
fn protect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    #[repr(C)]
    struct Blob { size: u32, data: *mut u8 }
    #[link(name = "crypt32")]
    extern "system" {
        fn CryptProtectData(input: *const Blob, description: *const u16, entropy: *mut Blob, reserved: *mut std::ffi::c_void, prompt: *mut std::ffi::c_void, flags: u32, output: *mut Blob) -> i32;
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn LocalFree(memory: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
    }
    let input = Blob { size: bytes.len() as u32, data: bytes.as_ptr() as *mut u8 };
    let mut output = Blob { size: 0, data: std::ptr::null_mut() };
    unsafe {
        if CryptProtectData(&input, std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), 0, &mut output) == 0 {
            return Err("Could not protect fleet secrets".into());
        }
        let value = std::slice::from_raw_parts(output.data, output.size as usize).to_vec();
        LocalFree(output.data.cast());
        Ok(value)
    }
}

#[cfg(windows)]
fn unprotect(bytes: &mut [u8]) -> Result<Vec<u8>, String> {
    #[repr(C)]
    struct Blob { size: u32, data: *mut u8 }
    #[link(name = "crypt32")]
    extern "system" {
        fn CryptUnprotectData(input: *mut Blob, description: *mut *mut u16, entropy: *mut Blob, reserved: *mut std::ffi::c_void, prompt: *mut std::ffi::c_void, flags: u32, output: *mut Blob) -> i32;
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn LocalFree(memory: *mut std::ffi::c_void) -> *mut std::ffi::c_void;
    }
    let mut input = Blob { size: bytes.len() as u32, data: bytes.as_mut_ptr() };
    let mut output = Blob { size: 0, data: std::ptr::null_mut() };
    unsafe {
        if CryptUnprotectData(&mut input, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), 1, &mut output) == 0 {
            return Err("Could not unlock fleet secrets".into());
        }
        let value = std::slice::from_raw_parts(output.data, output.size as usize).to_vec();
        LocalFree(output.data.cast());
        Ok(value)
    }
}

fn load_config() -> Result<Option<FleetConfig>, String> {
    let path = config_path()?;
    if !path.is_file() {
        return Ok(None);
    }
    #[cfg(windows)]
    {
        let mut bytes = fs::read(&path).map_err(|error| error.to_string())?;
        let plain = unprotect(&mut bytes)?;
        let config: FleetConfig = serde_json::from_slice(&plain).map_err(|error| error.to_string())?;
        return Ok(Some(config));
    }
    #[cfg(not(windows))]
    {
        let _ = path;
        Err("ChatGPT Attach secret storage is available on Windows Cyclone One".into())
    }
}

fn save_config(config: &FleetConfig) -> Result<(), String> {
    let dir = install_dir()?;
    fs::create_dir_all(&dir).map_err(|error| error.to_string())?;
    let bytes = serde_json::to_vec(config).map_err(|error| error.to_string())?;
    #[cfg(windows)]
    {
        let protected = protect(&bytes)?;
        fs::write(config_path()?, protected).map_err(|error| error.to_string())?;
        return Ok(());
    }
    #[cfg(not(windows))]
    {
        let _ = bytes;
        Err("ChatGPT Attach secret storage is available on Windows Cyclone One".into())
    }
}

fn redact(text: &str, secrets: &[String]) -> String {
    let mut out = text.to_string();
    for secret in secrets {
        if secret.len() >= 4 {
            out = out.replace(secret, "***");
        }
    }
    out
}

fn extract_json(stdout: &str) -> Result<Value, String> {
    let trimmed = stdout.trim();
    if let Ok(value) = serde_json::from_str::<Value>(trimmed) {
        return Ok(value);
    }
    if let (Some(start), Some(end)) = (trimmed.find('{'), trimmed.rfind('}')) {
        if end > start {
            if let Ok(value) = serde_json::from_str::<Value>(&trimmed[start..=end]) {
                return Ok(value);
            }
        }
    }
    Err("fleet sync did not return JSON".into())
}

fn adb_path() -> PathBuf {
    if let Some(local) = std::env::var_os("LOCALAPPDATA") {
        let bundled = PathBuf::from(local).join("Cyclone One").join("android-platform-tools").join("adb.exe");
        if bundled.is_file() {
            return bundled;
        }
    }
    PathBuf::from("adb")
}

#[cfg(windows)]
fn run_sync(app: &AppHandle, config: &FleetConfig) -> Result<Value, String> {
    let root = bundled_dir(app)?;
    let script = root.join("scripts").join("Sync-VmosFleet.ps1");
    let adb = adb_path();
    let runtime_dir = install_dir()?.join("runtime");
    fs::create_dir_all(&runtime_dir).map_err(|error| error.to_string())?;
    let fleet_file = runtime_dir.join("fleet.ephemeral.json");
    fs::write(&fleet_file, serde_json::to_vec(config).map_err(|error| error.to_string())?).map_err(|error| error.to_string())?;
    let mut command = Command::new("powershell.exe");
    command
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            &script.to_string_lossy(),
            "-Json",
            "-AdbPath",
            &adb.to_string_lossy(),
            "-FleetFile",
            &fleet_file.to_string_lossy(),
        ])
        .current_dir(&root)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW);
    let mut child = command.spawn().map_err(|error| error.to_string())?;
    let start = std::time::Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                if start.elapsed() > Duration::from_secs(180) {
                    let _ = child.kill();
                    let _ = fs::remove_file(&fleet_file);
                    return Err("fleet sync timed out".into());
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(error) => return Err(error.to_string()),
        }
    }
    let output = child.wait_with_output().map_err(|error| error.to_string())?;
    let _ = fs::remove_file(&fleet_file);
    let secrets: Vec<String> = config
        .pads
        .iter()
        .filter_map(|pad| pad.connect_key.clone())
        .chain(config.vmos_api_key.clone())
        .collect();
    let stdout = redact(&String::from_utf8_lossy(&output.stdout), &secrets);
    let stderr = redact(&String::from_utf8_lossy(&output.stderr), &secrets);
    match extract_json(&stdout) {
        Ok(mut value) => {
            if let Some(obj) = value.as_object_mut() {
                obj.entry("controlApi".to_string())
                    .or_insert_with(|| json!(config.control_api_base));
                let pads = obj.get("pads").cloned();
                match pads {
                    Some(item) if item.is_object() => {
                        obj.insert("pads".into(), json!([item]));
                    }
                    Some(item) if item.is_null() => {
                        obj.insert("pads".into(), json!([]));
                    }
                    None => {
                        obj.insert("pads".into(), json!([]));
                    }
                    _ => {}
                }
            }
            Ok(value)
        }
        Err(error) => Err(format!("{error}: {}", if stderr.trim().is_empty() { stdout.chars().take(240).collect::<String>() } else { stderr.chars().take(240).collect::<String>() })),
    }
}

#[cfg(not(windows))]
fn run_sync(_app: &AppHandle, _config: &FleetConfig) -> Result<Value, String> {
    Err("VMOS fleet sync is available on Windows Cyclone One".into())
}

#[tauri::command]
pub fn chatgpt_attach_load() -> Result<Value, String> {
    let config = load_config()?.unwrap_or(FleetConfig {
        control_api_base: String::new(),
        default_goal: "Observe assigned phones and wait for the next instruction.".into(),
        vmos_api_key: None,
        has_vmos_api_key: Some(false),
        pads: Vec::new(),
    });
    serde_json::to_value(public_config(config)).map_err(|error| error.to_string())
}

#[tauri::command]
pub fn chatgpt_attach_save(config: FleetConfig) -> Result<Value, String> {
    let merged = merge_secrets(load_config()?, config);
    save_config(&merged)?;
    serde_json::to_value(public_config(merged)).map_err(|error| error.to_string())
}

#[tauri::command]
pub fn chatgpt_attach_sync(app: AppHandle) -> Result<Value, String> {
    let config = load_config()?.ok_or("Save at least one VMOS pad before syncing.")?;
    if config.pads.is_empty() {
        return Err("Save at least one VMOS pad before syncing.".into());
    }
    run_sync(&app, &config)
}

#[tauri::command]
pub fn chatgpt_attach_copy(markdown: String) -> Result<Value, String> {
    reject_secrets(&markdown)?;
    #[cfg(windows)]
    {
        let mut command = Command::new("powershell.exe");
        command
            .args(["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "Set-Clipboard -Value $input"])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .creation_flags(CREATE_NO_WINDOW);
        let mut child = command.spawn().map_err(|error| error.to_string())?;
        if let Some(mut stdin) = child.stdin.take() {
            stdin.write_all(markdown.as_bytes()).map_err(|error| error.to_string())?;
        }
        let status = child.wait().map_err(|error| error.to_string())?;
        if !status.success() {
            return Err("Clipboard copy failed".into());
        }
        return Ok(json!({ "ok": true }));
    }
    #[cfg(not(windows))]
    {
        let _ = markdown;
        Err("Clipboard copy is available on Windows Cyclone One".into())
    }
}

#[tauri::command]
pub fn chatgpt_attach_save_handoff(markdown: String) -> Result<String, String> {
    reject_secrets(&markdown)?;
    let dir = install_dir()?;
    fs::create_dir_all(&dir).map_err(|error| error.to_string())?;
    let path = dir.join("FLEET_HANDOFF.md");
    fs::write(&path, markdown).map_err(|error| error.to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[tauri::command]
pub fn chatgpt_attach_resources(app: AppHandle) -> Result<Value, String> {
    let root = bundled_dir(&app)?;
    let openapi = read_text(&root.join("openapi-cloud-control.yaml"))?;
    let instructions = read_text(&root.join("CUSTOM_GPT_INSTRUCTIONS.md"))?;
    let example = read_text(&root.join("vmos.fleet.example.json"))?;
    Ok(json!({
        "openapi": openapi,
        "instructions": instructions,
        "exampleFleet": example,
    }))
}

fn read_text(path: &Path) -> Result<String, String> {
    fs::read_to_string(path).map_err(|error| format!("{}: {error}", path.display()))
}

fn reject_secrets(markdown: &str) -> Result<(), String> {
    let lowered = markdown.to_lowercase();
    for needle in ["connectkey:", "accesskey:", "secretaccesskey:", "vmosapikey:"] {
        if lowered.contains(needle) {
            return Err("Refusing to export a handoff that contains transport secrets".into());
        }
    }
    Ok(())
}
