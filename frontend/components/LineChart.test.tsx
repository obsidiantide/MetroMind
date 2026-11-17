import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LineChart, niceCeiling } from "@/components/LineChart";

describe("niceCeiling", () => {
  it("rounds up to 1, 2 or 5 times a power of ten", () => {
    expect(niceCeiling(0.7)).toBe(1);
    expect(niceCeiling(13)).toBe(20);
    expect(niceCeiling(420)).toBe(500);
    expect(niceCeiling(501)).toBe(1000);
    expect(niceCeiling(0)).toBe(1);
    expect(niceCeiling(Number.NaN)).toBe(1);
  });
});

describe("LineChart", () => {
  const series = [
    { label: "average", color: "orange", values: [10, 40, 25] },
    { label: "worst", color: "red", values: [30, 130, 60], dashed: true },
  ];

  it("labels the chart and shows each series' latest value in the legend", () => {
    render(<LineChart title="Train delay" unit="s" labels={["08:00", "08:01", "08:02"]} series={series} />);

    const figure = screen.getByRole("figure");
    expect(within(figure).getByText("Train delay")).toBeTruthy();
    expect(figure.querySelector(".chart-legend")?.textContent).toContain("average 25 s");
    expect(figure.querySelector(".chart-legend")?.textContent).toContain("worst 60 s");
    expect(screen.getByRole("img", { name: "Train delay" })).toBeTruthy();
  });

  it("scales the y axis to a round ceiling and labels first, middle and last samples", () => {
    const { container } = render(
      <LineChart title="Delay" unit="s" labels={["08:00", "08:01", "08:02"]} series={series} />);
    const texts = [...container.querySelectorAll("svg text")].map((t) => t.textContent);

    expect(texts).toEqual(expect.arrayContaining(["0", "50", "100", "150", "200", "08:00", "08:01", "08:02"]));
    const dashed = container.querySelectorAll("polyline[stroke-dasharray]");
    expect(dashed).toHaveLength(1);
    expect(dashed[0].getAttribute("stroke")).toBe("red");
  });

  it("lists every series' value at a sample in its hover title", () => {
    const { container } = render(
      <LineChart title="Delay" unit="s" labels={["08:00", "08:01", "08:02"]} series={series} />);
    const titles = [...container.querySelectorAll("rect.chart-hover title")].map((t) => t.textContent);

    expect(titles).toHaveLength(3);
    expect(titles[1]).toBe("08:01\naverage: 40 s\nworst: 130 s");
  });

  it("renders an empty chart without samples", () => {
    const { container } = render(<LineChart title="Empty" unit="" labels={[]} series={[]} />);
    expect(container.querySelectorAll("rect.chart-hover")).toHaveLength(0);
    expect(container.querySelector(".chart-legend")?.textContent).toBe("");
  });
});
