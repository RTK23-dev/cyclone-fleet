import { defineConfig } from "vite";
import { readFileSync } from "node:fs";

const glassVersion = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")).version as string;
// The Cyclone release this Glass ships in (release/version.toml), so the sidebar can say "in Cyclone 5.0.0-alpha.N".
const releaseVersion = (() => {
  try {
    const toml = readFileSync(new URL("../../release/version.toml", import.meta.url), "utf8");
    return /^product_version\s*=\s*"([^"]+)"/m.exec(toml)?.[1] ?? "";
  } catch {
    return "";
  }
})();
// Dev only: proxy API calls to the local gateway so `npm run dev` behaves like the served bundle.
const gateway = process.env.CYCLONE_GATEWAY_URL ?? "http://127.0.0.1:8765";

export default defineConfig({
  base: "/glass/",
  define: {
    __CYCLONE_GLASS_VERSION__: JSON.stringify(releaseVersion ? `${glassVersion} · in Cyclone ${releaseVersion.replace(/\.dev\d+$/, "")}` : glassVersion),
  },
  clearScreen: false,
  server: {
    host: "127.0.0.1",
    port: 5178,
    strictPort: true,
    proxy: {
      "/v1": { target: gateway, ws: true, changeOrigin: false },
    },
  },
  build: {
    target: "es2022",
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: true,
  },
});
