import { defineConfig } from "vite";
import solid from "vite-plugin-solid";

export default defineConfig({
  plugins: [solid()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://127.0.0.1:8766", changeOrigin: true },
    },
  },
  build: {
    outDir: "../src/main/resources/static",
    emptyOutDir: true,
  },
});
