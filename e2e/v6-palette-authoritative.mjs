import { chromium } from "playwright";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
page.on("request", request => requests.push({ url: request.url(), method: request.method() }));

const post = suffix =>
  page.waitForResponse(response =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname.endsWith(suffix));
const rf = id => page.locator('.react-flow__node[data-id="' + id + '"]');

try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  let pending = post("/api/pipelines/open");
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openResponse = await pending;
  let graph = await openResponse.json();
  if (!openResponse.ok() || !graph.name || graph.nodes?.length < 2 || graph.edges?.length < 1) {
    throw new Error(`Unexpected authoritative Palette graph: ${JSON.stringify(graph)}`);
  }
  await page.locator(`.file-identity[data-document-name="${graph.name}"]`).waitFor({ state: "visible" });
  await page.locator(".react-flow").waitFor({ state: "visible" });

  const canvas = page.locator(".react-flow__pane");
  const added = [];
  const plugins = ["TableInput", "SelectValues", "FilterRows"];
  const positions = [{ x: 560, y: 260 }, { x: 760, y: 360 }, { x: 960, y: 260 }];

  for (let i = 0; i < plugins.length; i++) {
    const before = new Set(graph.nodes.map(node => node.id));
    pending = post("/edit/add");
    await page.getByRole("button", { name: plugins[i] }).dragTo(canvas, { targetPosition: positions[i] });
    const response = await pending;
    if (!response.ok()) throw new Error(`add ${plugins[i]} HTTP ${response.status()}: ${await response.text()}`);
    graph = await response.json();
    const node = graph.nodes.find(candidate => !before.has(candidate.id));
    if (!node || node.pluginId !== plugins[i]) {
      throw new Error(`authoritative add mismatch for ${plugins[i]}: ${JSON.stringify(node)}`);
    }
    if (!node.id || before.has(node.id)) throw new Error(`node id not unique for ${plugins[i]}`);
    added.push(node);
    await rf(node.id).waitFor();
  }

  async function connect(from, to) {
    pending = post("/edit/connect");
    const sourceHandle = rf(from).locator(".react-flow__handle.source");
    const targetHandle = rf(to).locator(".react-flow__handle.target");
    const sourceBox = await sourceHandle.boundingBox();
    const targetBox = await targetHandle.boundingBox();
    if (!sourceBox || !targetBox) throw new Error(`connect handles missing for ${from}->${to}`);
    await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, { steps: 12 });
    await page.mouse.up();
    const response = await pending;
    const body = await response.text();
    if (!response.ok()) throw new Error(`connect HTTP ${response.status()}: ${body}`);
    graph = JSON.parse(body);
    if (!graph.edges.some(edge => edge.source === from && edge.target === to)) {
      throw new Error(`authoritative hop missing for ${from}->${to}`);
    }
  }

  await connect(added[0].id, added[1].id);
  await connect(added[1].id, added[2].id);

  pending = post("/config/read");
  await rf(added[0].id).dblclick();
  const initialRead = await pending;
  const initial = await initialRead.json();
  if (!initialRead.ok() || initial.pluginId !== "TableInput") {
    throw new Error("new TableInput did not open authoritative GenericConfigPanel");
  }
  const sqlDescriptor = initial.descriptor?.properties?.find(property => property.key === "sql");
  if (!sqlDescriptor) throw new Error("TableInput sql descriptor missing");

  const panel = page.locator("form.generic-config");
  await panel.waitFor({ state: "visible" });
  const sqlField = panel.locator(`#config-${sqlDescriptor.javaField}`);
  const sql = "SELECT 42 AS answer";
  await sqlField.fill(sql);

  const writePending = post("/config/write");
  const rereadPending = post("/config/read");
  await panel.getByRole("button", { name: "Save" }).click();
  const writeResponse = await writePending;
  const written = await writeResponse.json();
  if (!writeResponse.ok() || written.config?.sql !== sql) throw new Error("config/write did not persist sql");
  const rereadResponse = await rereadPending;
  const reread = await rereadResponse.json();
  if (!rereadResponse.ok() || reread.config?.sql !== sql) throw new Error("config re-read did not return sql");
  await panel.getByRole("button", { name: "Cancel" }).click();

  pending = post("/save");
  await page.getByRole("button", { name: "Save" }).click();
  const saved = await (await pending).json();
  for (const node of added) {
    if (!saved.nodes.some(candidate => candidate.id === node.id && candidate.pluginId === node.pluginId)) {
      throw new Error(`save lost ${node.pluginId}`);
    }
  }
  if (!saved.edges.some(edge => edge.source === added[0].id && edge.target === added[1].id) ||
      !saved.edges.some(edge => edge.source === added[1].id && edge.target === added[2].id)) {
    throw new Error("save lost Palette hops");
  }

  pending = post("/api/pipelines/open");
  await page.getByRole("button", { name: "Choose pipeline" }).click();
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const reopened = await (await pending).json();
  for (const node of added) {
    if (!reopened.nodes.some(candidate => candidate.id === node.id && candidate.pluginId === node.pluginId)) {
      throw new Error(`disk reopen lost ${node.pluginId}`);
    }
  }
  if (!reopened.edges.some(edge => edge.source === added[0].id && edge.target === added[1].id) ||
      !reopened.edges.some(edge => edge.source === added[1].id && edge.target === added[2].id)) {
    throw new Error("disk reopen lost Palette hops");
  }

  pending = post("/config/read");
  await rf(added[0].id).dblclick();
  const reopenedConfig = await (await pending).json();
  if (reopenedConfig.config?.sql !== sql) throw new Error("disk reopen lost authoritative config");

  const rap = requests.filter(({ url }) => {
    const pathname = new URL(url).pathname;
    return pathname === "/ui" || pathname.startsWith("/ui/");
  });
  if (rap.length) throw new Error("RAP /ui entered");

  console.log("V6 Palette authoritative proof passed: TableInput, SelectValues, FilterRows");
} finally {
  await browser.close();
}
