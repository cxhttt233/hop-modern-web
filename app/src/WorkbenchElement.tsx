import { StrictMode } from "react";
import { createRoot, type Root } from "react-dom/client";
import "@xyflow/react/dist/style.css";
import "./styles.css";
import { App } from "./App";
import { enableWorkbenchApi } from "./workbenchTransport";

/** Workbench integration point: React is mounted INSIDE Vue, never in an iframe. */
class HopWorkbenchEditor extends HTMLElement {
  static observedAttributes = ["path"];
  private root: Root | null = null;
  private renderVersion = 0;
  private saveHandler: (() => void) | null = null;

  reload(): void { this.renderVersion++; this.renderEditor(); }
  save(): void { this.saveHandler?.(); }

  connectedCallback(): void {
    const shadow = this.shadowRoot ?? this.attachShadow({ mode: "open" });
    if (!shadow.querySelector("link")) {
      const style = document.createElement("link");
      style.rel = "stylesheet";
      style.href = "/workbench-modern/editor.css";
      shadow.appendChild(style);
      const overrides = document.createElement("style");
      overrides.textContent = `
        :host{display:block;width:100%;height:100%;min-height:0;color:var(--el-text-color-primary,#303133);font-family:'Microsoft YaHei UI','PingFang SC',sans-serif;--wb-accent:var(--el-color-primary,#409eff)}
        *{box-sizing:border-box}
        .shell{height:100%;min-height:0;grid-template-rows:44px minmax(0,1fr);background:var(--el-bg-color-page,#f5f7fa)}
        .topbar{grid-template-columns:minmax(0,1fr) auto;gap:12px;padding:0 16px;background:var(--el-bg-color,#fff);border-bottom:1px solid var(--el-border-color-lighter,#ebeef5);box-shadow:none}
        .product-brand{display:none}
        .file-identity{justify-self:start;padding:0;border-radius:0}
        .file-identity strong{font-size:13px;font-weight:600;color:var(--el-text-color-primary,#303133)}
        .workspace{background:var(--el-bg-color,#fff)}
        .transform-palette{background:var(--el-bg-color,#fff);border-right:1px solid var(--el-border-color-lighter,#ebeef5)}
        .palette-heading strong{color:var(--el-text-color-primary,#303133)}
        .transform-items button{color:var(--el-text-color-primary,#303133)}
        .transform-items button:hover{background:var(--el-fill-color-light,#f5f7fa);border-color:var(--el-border-color-light,#e4e7ed)}
        .palette-heading>span{background:var(--el-fill-color-light,#f5f7fa);color:var(--el-text-color-secondary,#909399)}
        .palette-search{border-color:var(--el-border-color,#dcdfe6);border-radius:4px}
        .palette-search input{color:var(--el-text-color-primary,#303133)}
        .canvas-chrome{background:var(--el-bg-color,#fff)}
        .empty-graph-state{color:var(--el-text-color-primary,#303133)}
        .empty-graph-state p,.empty-graph-state small{color:var(--el-text-color-secondary,#909399)}
        .embedded-readonly-indicator{color:var(--el-text-color-secondary,#909399)}
      `;
      shadow.appendChild(overrides);
    }
    let mount = shadow.querySelector<HTMLDivElement>("#workbench-react-root");
    if (!mount) {
      mount = document.createElement("div");
      mount.id = "workbench-react-root";
      mount.style.cssText = "width:100%;height:100%;min-height:0;overflow:hidden";
      shadow.appendChild(mount);
    }
    if (!this.root) this.root = createRoot(mount);
    this.renderEditor();
  }

  attributeChangedCallback(): void {
    if (this.isConnected) this.renderEditor();
  }

  private renderEditor(): void {
    enableWorkbenchApi();
    const path = this.getAttribute("path") ?? "";
    this.root?.render(<StrictMode><App key={path + ':' + this.renderVersion} initialPath={path}
      embedded onRegisterSave={(handler) => { this.saveHandler = handler; }}
      onSaveResult={(result) => this.dispatchEvent(new CustomEvent("workbench-modern-save-result", {
        detail: result, bubbles: true,
      }))} /></StrictMode>);
  }

  disconnectedCallback(): void {
    this.root?.unmount();
    this.root = null;
    this.saveHandler = null;
  }
}
if (!customElements.get("hop-workbench-editor")) {
  customElements.define("hop-workbench-editor", HopWorkbenchEditor);
}