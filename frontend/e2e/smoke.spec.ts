import { expect, test } from "@playwright/test";
import type { MetroNetwork } from "../lib/types";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

test("dashboard draws the live network and opens a station drawer", async ({ page, request }) => {
  const res = await request.get(`${API_URL}/api/network`);
  expect(res.ok()).toBeTruthy();
  const network = (await res.json()) as MetroNetwork;
  const station = network.stations[0];

  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(network.city);
  const map = page.getByRole("img", { name: "Metro network map" });
  await expect(map).toBeVisible();
  // One track per line, and the simulation feed has placed its trains on the map.
  await expect(map.locator("polyline[data-track]")).toHaveCount(
    network.lines.reduce((n, l) => n + 1 + (l.branches?.length ?? 0), 0));
  await expect(page.locator("header").getByText(/· live ·/)).toBeVisible();
  await expect(map.locator("rect")).not.toHaveCount(0);

  await map.getByText(station.name, { exact: true }).click();
  const drawer = page.getByRole("complementary", { name: "Details" });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole("heading", { name: station.name })).toBeVisible();
  await expect(drawer.getByRole("heading", { name: "Departures" })).toBeVisible();
  await expect(drawer.getByRole("table").last()).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(drawer).toBeHidden();
});
