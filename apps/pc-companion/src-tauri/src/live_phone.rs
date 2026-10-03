use serde_json::{json, Value};
use std::path::PathBuf;

fn root() -> Result<PathBuf, String> {
    let local = std::env::var_os("LOCALAPPDATA").ok_or("Live Phone requires Windows")?;
    let path = PathBuf::from(local).join("Cyclone One").join("live-phone");
    std::fs::create_dir_all(&path).map_err(|_| "Could not prepare Live Phone")?;
    Ok(path)
}

#[tauri::command]
pub fn live_phone_control(action: String) -> Result<Value, String> {
    if !["enable", "pause", "stop"].contains(&action.as_str()) { return Err("Unknown Live Phone control".into()); }
    let path = root()?;
    let generation = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_nanos().to_string();
    let state = json!({"enabled": action == "enable", "stopped": action == "stop", "generation": generation});
    std::fs::write(path.join("control.json"), state.to_string()).map_err(|_| "Could not change Live Phone control")?;
    if action == "stop" {
        for name in ["latest.png", "latest.jpg"] { let _ = std::fs::remove_file(path.join(name)); }
    }
    Ok(state)
}

#[tauri::command]
pub fn live_phone_status() -> Result<Value, String> {
    let path = root()?;
    let state = std::fs::read(path.join("status.json")).ok()
        .and_then(|v| serde_json::from_slice::<Value>(&v).ok()).unwrap_or(json!({}));
    let control = std::fs::read(path.join("control.json")).ok()
        .and_then(|v| serde_json::from_slice::<Value>(&v).ok()).unwrap_or(json!({}));
    let enabled = control["enabled"].as_bool().unwrap_or(false);
    let stopped = control["stopped"].as_bool().unwrap_or(true);
    let same_generation = state["generation"] == control["generation"];
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs();
    let recent = now.saturating_sub(state["at"].as_u64().unwrap_or(0)) < 15;
    let fresh = |name: &str| -> bool {
        std::fs::read(path.join(name)).ok().and_then(|v| serde_json::from_slice::<Value>(&v).ok())
            .and_then(|v| v["at"].as_u64()).map(|at| now.saturating_sub(at) < 15).unwrap_or(false)
    };
    let broker_ready = fresh("broker-status.json");
    Ok(json!({"live_phone_broker_ready": broker_ready, "gateway_ready": broker_ready && recent && state["gateway_ready"].as_bool().unwrap_or(false), "accessibility_ready": broker_ready && recent && same_generation && !stopped && state["accessibility_ready"].as_bool().unwrap_or(false), "cloud_connector_ready": broker_ready && fresh("connector-status.json"), "connected": recent, "enabled": enabled, "stopped": stopped, "vision": recent && same_generation && !stopped && state["vision"].as_bool().unwrap_or(false), "control": recent && same_generation && enabled && !stopped && state["control"].as_bool().unwrap_or(false)}))
}
