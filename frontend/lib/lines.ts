import type { Line } from "@/lib/types";

/** Every station a line serves: its main route, then each branch's stations. */
export function lineStationIds(line: Line): string[] {
  return [...line.stationIds, ...(line.branches ?? []).flatMap((b) => b.stationIds)];
}

/**
 * Every track section of a line as [from, to] station ids, in running order: along the main route (and round
 * a ring back to its first station), then each branch from its junction outwards.
 */
export function lineSections(line: Line): [string, string][] {
  const main = line.stationIds;
  const sections: [string, string][] = main.slice(1).map((to, i) => [main[i], to]);
  if (line.ring && main.length > 2) sections.push([main[main.length - 1], main[0]]);
  for (const branch of line.branches ?? []) {
    const stops = [branch.junctionStationId, ...branch.stationIds];
    sections.push(...stops.slice(1).map((to, i): [string, string] => [stops[i], to]));
  }
  return sections;
}
