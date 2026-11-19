import type { Line, Station } from "@/lib/types";

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

/**
 * Stations a service pattern calls at: from the line's `patterns` when the network lists them, otherwise the main
 * route for the line's own id, or the trunk up to the junction plus the branch for `line/branch-terminal`.
 */
export function patternStationIds(line: Line, patternId: string): string[] {
  const listed = line.patterns?.find((p) => p.id === patternId);
  if (listed) return listed.stationIds;
  const branch = (line.branches ?? []).find((b) => `${line.id}/${b.stationIds[b.stationIds.length - 1]}` === patternId);
  if (!branch) return line.stationIds;
  const junction = line.stationIds.indexOf(branch.junctionStationId);
  return [...line.stationIds.slice(0, junction + 1), ...branch.stationIds];
}

/** Whether a station is the far end of one of the line's branches, so trains bound for it leave the main route. */
export function isBranchTerminal(line: Line, stationId: string): boolean {
  return (line.branches ?? []).some((b) => b.stationIds[b.stationIds.length - 1] === stationId);
}

/**
 * Where a train is bound for, as shown on its marker and in its drawer: "to Bakmil" towards a terminal (with the
 * branch named as such), or "next Foundry" on a ring, whose trains are signed by the next station.
 */
export function boundFor(line: Line | undefined, towardsName: string, towardsStationId: string): string {
  if (line?.ring) return `next ${towardsName}`;
  return line && isBranchTerminal(line, towardsStationId) ? `to ${towardsName} (branch)` : `to ${towardsName}`;
}

export type RingTurn = "clockwise" | "anticlockwise";

/**
 * Which way a ring's trains run round it on a north-up map, from the signed area of its stations taken in running
 * order (positive when they turn anticlockwise); null for a line that is not a ring.
 */
export function ringTurn(line: Line, station: (id: string) => Station): RingTurn | null {
  if (!line.ring || line.stationIds.length < 3) return null;
  const stops = line.stationIds.map(station);
  const area = stops.reduce((sum, a, i) => {
    const b = stops[(i + 1) % stops.length];
    return sum + a.lon * b.lat - b.lon * a.lat;
  }, 0);
  return area < 0 ? "clockwise" : "anticlockwise";
}

/** Seconds since midnight of an HH:mm time of day. */
function clockSeconds(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 3600 + m * 60;
}

/**
 * Trains a line should run at simulated time `simTime` (seconds since midnight of the first day): the fleet plan's
 * period in force, the last one carrying on past midnight, or the line's trainCount when it has no plan. Mirrors
 * Line.plannedTrains on the backend.
 */
export function plannedTrains(line: Line, simTime: number): number {
  const plan = line.fleetPlan ?? [];
  if (plan.length === 0) return line.trainCount;
  const timeOfDay = ((simTime % 86_400) + 86_400) % 86_400;
  let current = plan[plan.length - 1];
  for (const period of plan) {
    if (clockSeconds(period.from) <= timeOfDay) current = period;
  }
  return current.trains;
}
