import { chromium } from "playwright";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
page.on("request", (request) => requests.push(request.url()));

function translation(style) {
  const match = /translate\(([-\d.]+)px,\s*([-\d.]+)px\)/.exec(style ?? "");
  if (!match) throw new Error(`Unable to read React Flow node translation from style: ${style}`);
  return { x: Number(match[1]), y: Number(match[2]) };
}

try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);

  const openResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      new URL(response.url()).pathname === "/api/pipelines/open"
  );
  await page.getByRole("button", { name: "Open pipeline" }).click();
  const openResponse = await openResponsePromise;
  if (!openResponse.ok()) throw new Error(`Pipeline open failed with ${openResponse.status()}`);
  const opened = await openResponse.json();

  await page.getByText("Server graph").waitFor({ state: "visible" });
  const source = page.locator('.react-flow__node[data-id="source"]');
  await source.waitFor({ state: "visible" });

  const beforeStyle = await source.getAttribute("style");
  const beforeUi = translation(beforeStyle);
  const beforeGraph = opened.nodes.find((node) => node.id === "source");
  if (!beforeGraph) throw new Error("Opened graph did not contain source transform");

  const box = await source.boundingBox();
  if (!box) throw new Error("Source node has no bounding box");
  const moveResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "POST" &&
      /\/api\/pipelines\/[^/]+\/move$/.test(new URL(response.url()).pathname)
  );

  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2 + 48, box.y + box.height / 2 + 32, { steps: 8 });
  await page.mouse.up();

  const moveResponse = await moveResponsePromise;
  if (!moveResponse.ok()) throw new Error(`Transform move failed with ${moveResponse.status()}`);
  const moved = await moveResponse.json();
  const movedGraph = moved.nodes.find((node) => node.id === "source");
  if (!movedGraph) throw new Error("Move response did not contain source transform");
  if (movedGraph.x === beforeGraph.x && movedGraph.y === beforeGraph.y) {
    throw new Error("Authoritative move response did not change source coordinates");
  }
  if (moved.revision === opened.revision) {
    throw new Error("Authoritative move response did not change revision");
  }

  await page.waitForFunction(
    ({ x, y }) => {
      const element = document.querySelector('.react-flow__node[data-id="source"]');
      if (!element) return false;
      const style = element.getAttribute("style") ?? "";
      const match = /translate\(([-\d.]+)px,\s*([-\d.]+)px\)/.exec(style);
      return !!match && Math.abs(Number(match[1]) - x) < 0.01 && Math.abs(Number(match[2]) - y) < 0.01;
    },
    { x: movedGraph.x, y: movedGraph.y }
  );

  const afterUi = translation(await source.getAttribute("style"));
  if (afterUi.x === beforeUi.x && afterUi.y === beforeUi.y) {
    throw new Error("React Flow did not end at the returned authoritative coordinates");
  }

  const moveRequests = requests.filter((url) =>
    /\/api\/pipelines\/[^/]+\/move$/.test(new URL(url).pathname)
  );
  if (moveRequests.length !== 1) {
    throw new Error(`Expected exactly one semantic move request, saw ${moveRequests.length}`);
  }

  const rapRequests = requests.filter((url) => {
    const path = new URL(url).pathname;
    return path === "/ui" || path.startsWith("/ui/");
  });
  if (rapRequests.length) throw new Error("RAP /ui must not be entered");

  console.log(
    `V3 proof passed: source (${beforeGraph.x},${beforeGraph.y}) -> (${movedGraph.x},${movedGraph.y}); one semantic move request; authoritative revision changed; UI reconciled; no RAP /ui.`
  );
} finally {
  await browser.close();
}
