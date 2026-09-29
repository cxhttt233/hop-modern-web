import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import path from "node:path";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createServer } from "vite";

const here = path.dirname(fileURLToPath(import.meta.url));
const appRoot = path.resolve(here, "../app");
const vite = await createServer({
  root: appRoot,
  appType: "custom",
  server: { middlewareMode: true },
});

try {
  const { GenericConfigPanel } = await vite.ssrLoadModule(
    "/src/editor/GenericConfigPanel.tsx",
  );

  const descriptor = {
    className: "L3RenderProbe",
    properties: [
      { key: "enabled", javaField: "enabled", shape: "BOOLEAN", javaType: "boolean" },
      { key: "limit", javaField: "limit", shape: "NUMBER", javaType: "int" },
      { key: "mode", javaField: "mode", shape: "ENUM", javaType: "Mode" },
      { key: "options", javaField: "options", shape: "OBJECT", javaType: "Options" },
      { key: "fields", javaField: "fields", shape: "LIST", javaType: "java.util.List" },
      { key: "sql", javaField: "sql", shape: "STRING", javaType: "java.lang.String" },
      { key: "name", javaField: "name", shape: "STRING", javaType: "java.lang.String" },
    ],
  };

  const html = renderToStaticMarkup(
    React.createElement(GenericConfigPanel, {
      descriptor,
      config: {
        enabled: true,
        limit: 10,
        mode: "AUTO",
        options: { sample: true },
        fields: [{ name: "id" }],
        sql: "select 1",
        name: "probe",
      },
      onCancel() {},
      onSave() {},
    }),
  );

  assert.match(html, /type="checkbox"/, "BOOLEAN must render checkbox");
  assert.match(html, /type="number"/, "NUMBER must render number input");
  assert.match(html, /<textarea[^>]*id="config-options"/, "OBJECT must render JSON textarea fallback");
  assert.match(html, /<textarea[^>]*id="config-fields"/, "LIST must render JSON textarea fallback");
  assert.match(html, /<textarea[^>]*id="config-sql"/, "SQL-like STRING must render textarea");
  assert.match(html, /<input[^>]*id="config-name"/, "plain STRING must render text input");
  assert.match(
    html,
    /<input[^>]*id="config-mode"[^>]*type="text"/,
    "ENUM currently falls back to text input and must not be misclassified as a select",
  );
  assert.doesNotMatch(html, /<select/, "current GenericConfigPanel has no ENUM select");

  console.log(
    "GENERIC_CONFIG_RENDER_PROBE=" +
      JSON.stringify({
        BOOLEAN: "CHECKBOX",
        NUMBER: "NUMBER_INPUT",
        ENUM: "TEXT_INPUT_FALLBACK",
        OBJECT: "JSON_TEXTAREA_FALLBACK",
        LIST: "JSON_TEXTAREA_FALLBACK",
        LONG_STRING: "TEXTAREA",
        STRING: "TEXT_INPUT",
      }),
  );
} finally {
  await vite.close();
}
