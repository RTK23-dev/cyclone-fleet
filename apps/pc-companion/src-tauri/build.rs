use std::{env, fs, path::PathBuf};

fn main() {
    ensure_windows_icon();
    tauri_build::build()
}

fn ensure_windows_icon() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let icons = manifest.join("icons");
    let icon = icons.join("icon.ico");
    if icon.exists() {
        return;
    }
    fs::create_dir_all(&icons).expect("create Tauri icons directory");
    fs::write(&icon, cyclone_ico()).expect("write deterministic Cyclone Windows icon");
    println!("cargo:rerun-if-changed={}", icon.display());
}

fn cyclone_ico() -> Vec<u8> {
    const W: usize = 32;
    const H: usize = 32;
    const PIXELS: usize = W * H * 4;
    const MASK: usize = H * 4; // 1-bit AND mask, rows padded to 32 bits.
    const IMAGE_BYTES: usize = 40 + PIXELS + MASK;
    const OFFSET: usize = 6 + 16;

    let mut out = Vec::with_capacity(OFFSET + IMAGE_BYTES);
    // ICONDIR
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    // ICONDIRENTRY
    out.push(W as u8);
    out.push(H as u8);
    out.push(0);
    out.push(0);
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&32u16.to_le_bytes());
    out.extend_from_slice(&(IMAGE_BYTES as u32).to_le_bytes());
    out.extend_from_slice(&(OFFSET as u32).to_le_bytes());
    // BITMAPINFOHEADER. ICO BMP height includes XOR + AND planes.
    out.extend_from_slice(&40u32.to_le_bytes());
    out.extend_from_slice(&(W as i32).to_le_bytes());
    out.extend_from_slice(&((H * 2) as i32).to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&32u16.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&(PIXELS as u32).to_le_bytes());
    out.extend_from_slice(&0i32.to_le_bytes());
    out.extend_from_slice(&0i32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&0u32.to_le_bytes());

    // Bottom-up BGRA image. A restrained purple cyclone ring on charcoal.
    let cx = (W as f32 - 1.0) / 2.0;
    let cy = (H as f32 - 1.0) / 2.0;
    for y in (0..H).rev() {
        for x in 0..W {
            let dx = x as f32 - cx;
            let dy = y as f32 - cy;
            let r = (dx * dx + dy * dy).sqrt();
            let angle = dy.atan2(dx);
            let spiral = 9.0 + 2.3 * (angle * 1.7).sin();
            let ring = (r - spiral).abs() < 2.3 || (r > 3.0 && r < 5.2);
            let (red, green, blue) = if ring {
                let glow = (210.0 - r * 3.0).clamp(120.0, 220.0) as u8;
                (glow, 92u8, 246u8)
            } else {
                (18u8, 18u8, 24u8)
            };
            out.extend_from_slice(&[blue, green, red, 255]);
        }
    }
    out.extend(std::iter::repeat_n(0u8, MASK));
    out
}
