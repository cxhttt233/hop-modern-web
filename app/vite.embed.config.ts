import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  root: path.resolve(import.meta.dirname),
  plugins: [react()],
  // React/ReactFlow use process.env.NODE_ENV internally. The embedded ESM
  // bundle runs directly in browsers, where Node's process object is absent.
  define: {
    "process.env.NODE_ENV": JSON.stringify("production"),
  },
  build: {
    outDir: path.resolve(import.meta.dirname, "../workbench-embed-dist"),
    emptyOutDir: true,
    cssCodeSplit: false,
    lib: {
      entry: path.resolve(import.meta.dirname, "src/WorkbenchElement.tsx"),
      formats: ["es"],
      fileName: () => "editor.js",
      cssFileName: "editor",
    },
    rollupOptions: { output: { inlineDynamicImports: true } },
  },
});