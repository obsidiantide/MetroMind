import { expect, test } from "@playwright/test";

test("a delay what-if ranks the strategies and draws its extra wait on the map", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("header").getByText(/· live ·/)).toBeVisible();

  const train = page.getByLabel("Train", { exact: true });
  await expect(train.locator("option")).not.toHaveCount(0);
  const trainId = await train.inputValue();
  await page.getByLabel("Seconds", { exact: true }).fill("240");
  await page.getByRole("button", { name: "What-if analysis" }).click();

  // The analysis forks the live engine for every strategy, which takes a few seconds on the standalone backend.
  const title = page.getByRole("heading", { name: `Impact of 4 min delay on ${trainId}` });
  await expect(title).toBeVisible({ timeout: 45_000 });
  const report = page.locator("section.panel").filter({ has: title });
  const rows = report.locator("tbody tr");
  await expect(rows).not.toHaveCount(0);

  // The best strategy's extra wait is on the map by default, with a legend naming it.
  const best = report.locator("tbody tr.best td").first();
  const bestStrategy = (await best.innerText()).replace(/ · on map$/, "");
  const map = page.getByRole("img", { name: "Metro network map" });
  const legend = map.getByLabel("What-if heat legend");
  await expect(legend).toContainText(`what-if: ${bestStrategy}`);

  // Picking another strategy's row maps its outcome instead.
  const other = report.locator("tbody tr:not(.best)").first();
  const otherStrategy = await other.locator("td").first().innerText();
  await other.click();
  await expect(legend).toContainText(`what-if: ${otherStrategy}`);
  await expect(other).toContainText("on map");

  await report.getByRole("button", { name: "Hide from map" }).click();
  await expect(legend).toBeHidden();
});
