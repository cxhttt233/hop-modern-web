import { chromium } from "playwright";

const pipelinePath = process.env.REAL_HPL_PATH;
if (!pipelinePath) throw new Error("REAL_HPL_PATH is required");

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage();
const requests = [];
page.on("request", (request) => requests.push(request.url()));

try {
  await page.goto("http://127.0.0.1:5173", { waitUntil: "networkidle" });
  await page.getByLabel("Server-visible pipeline path").fill(pipelinePath);
  await page.getByRole("button", { name: "Open pipeline" }).click();
  await page.getByText("Server graph").waitFor({ state: "visible" });
  await page.getByText("real-graph-proof", { exact: true }).waitFor({ state: "visible" });
  const openRequests = requests.filter((url) => url.includes("/api/pipelines/open"));
  if (openRequests.length !== 1) throw new Error("Expected one real pipeline open request");
  const rapRequests = requests.filter((url) => {
    const path = new URL(url).pathname;
    return path === "/ui" || path.startsWith("/ui/");
  });
  if (rapRequests.length) throw new Error("RAP /ui must not be entered");
  console.log("V2 proof passed: real .hpl -> /api/pipelines/open -> Server graph; no RAP /ui requests.");
} finally {
  await browser.close();
}
