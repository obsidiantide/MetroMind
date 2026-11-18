import type { MetroNetwork, MinuteKpi, Snapshot, StationView, TrainView } from "@/lib/types";

/** Two lines crossing at Central: enough to exercise interchanges, queues and colours. */
export const network: MetroNetwork = {
  city: "Testville",
  stations: [
    { id: "A", name: "Alpha", lat: 40.0, lon: 49.8, baseArrivalsPerMinute: 4 },
    { id: "C", name: "Central", lat: 40.0, lon: 49.82, baseArrivalsPerMinute: 10, platformCapacity: 400 },
    { id: "B", name: "Bravo", lat: 40.0, lon: 49.84, baseArrivalsPerMinute: 4 },
    { id: "N", name: "North", lat: 40.02, lon: 49.82, baseArrivalsPerMinute: 3 },
  ],
  lines: [
    { id: "RED", name: "Red Line", color: "#e53935", stationIds: ["A", "C", "B"], runSeconds: [180, 180],
      trainCount: 2, trainCapacity: 900, spareTrains: 0 },
    { id: "BLUE", name: "Blue Line", color: "#1e88e5", stationIds: ["C", "N"], runSeconds: [200],
      trainCount: 1, trainCapacity: 900, spareTrains: 0 },
  ],
};

export function stationView(id: string, over: Partial<StationView> = {}): StationView {
  return {
    id, waiting: 0, predictedWaiting15m: 0, overloadRisk: "LOW", heldAtEntrance: 0, leftBehind: 0, queues: [],
    closed: false, ...over,
  };
}

export function trainView(id: string, over: Partial<TrainView> = {}): TrainView {
  return {
    id, lineId: "RED", patternId: "RED", fromStationId: "A", toStationId: "C", progress: 0.5, lat: 40.0, lon: 49.81,
    phase: "RUNNING", delaySeconds: 0, load: 300, capacity: 900, failed: false, ...over,
  };
}

export function snapshot(over: Partial<Snapshot> = {}): Snapshot {
  return {
    simTime: 8 * 3600, clock: "08:00:00", strategy: "BALANCED", trains: [], stations: [], recentEvents: [],
    closedSections: [], activeIncidents: [],
    metrics: {
      averageDelaySeconds: 0, maxDelaySeconds: 0, totalWaiting: 0, passengersServed: 0, passengersDelivered: 0,
      transfers: 0, passengersLeftBehind: 0, passengersHeldAtEntrance: 0, headwayCoefficientOfVariation: 0,
      trainsInService: 0, trainsInDepot: 0, stopsSkipped: 0, passengersCarriedPast: 0,
    },
    ...over,
  };
}

export function minuteKpi(minute: number, over: Partial<MinuteKpi> = {}): MinuteKpi {
  const clock = `${String(Math.floor(minute / 60) % 24).padStart(2, "0")}:${String(minute % 60).padStart(2, "0")}`;
  return {
    minute, clock, trainsReporting: 3, averageDelaySeconds: 0, maxDelaySeconds: 0, averageLoadFactor: 0.3,
    averageWaiting: 0, maxWaiting: 0, averageHeadwayCv: 0.05, delayAlerts: 0, incidents: 0, ...over,
  };
}
