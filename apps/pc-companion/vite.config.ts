import { defineConfig } from "vite";
import { readFileSync } from "node:fs";

const companionVersion = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")).version as string;

export default defineConfig({
  define: {
    __CYCLONE_PC_VERSION__: JSON.stringify(companionVersion),
  },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
  },
  envPrefix: ["VITE_", "TAURI_ENV_*"],
  build: {
    target: "es2022",
    sourcemap: true,
  },
});
