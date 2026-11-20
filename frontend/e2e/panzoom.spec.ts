import { expect, test } from "@playwright/test";

test("the map zooms with the wheel, pans by dragging and returns to the whole network", async ({ page }) => {
  await page.goto("/");
  const map = page.getByRole("img", { name: "Metro network map" });
  await expect(map).toBeVisible();
  const content = map.locator("[data-viewport]");
  await expect(content).toHaveAttribute("transform", "translate(0,0) scale(1)");

  // The map starts part-way down the page; bring all of it on screen so the pointer stays over it.
  await map.scrollIntoViewIfNeeded();
  const box = (await map.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.wheel(0, -600);
  await expect(content).not.toHaveAttribute("transform", "translate(0,0) scale(1)");
  const zoomed = await content.getAttribute("transform");

  // Drag from an empty corner of the network.
  await page.mouse.move(box.x + box.width * 0.9, box.y + box.height * 0.9);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.7, box.y + box.height * 0.8, { steps: 5 });
  await page.mouse.up();
  await expect(content).not.toHaveAttribute("transform", zoomed!);
  await expect(page.getByRole("complementary", { name: "Details" })).toBeHidden();

  await map.getByRole("button", { name: "Reset map view" }).click();
  await expect(content).toHaveAttribute("transform", "translate(0,0) scale(1)");

  await map.focus();
  await page.keyboard.press("+");
  await expect(content).toHaveAttribute("transform", /scale\(1\.5\)$/);
  await page.keyboard.press("0");
  await expect(content).toHaveAttribute("transform", "translate(0,0) scale(1)");
});
