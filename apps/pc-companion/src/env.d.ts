interface ImportMetaEnv {
  readonly VITE_CYCLONE_BACKEND?: "real" | "mock";
  readonly VITE_CYCLONE_MOCK_DEVICES?: string;
  readonly VITE_CYCLONE_HTTP_BASE?: string;
  readonly VITE_CYCLONE_WS_BASE?: string;
  readonly VITE_CYCLONE_GATEWAY_TOKEN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
