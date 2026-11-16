export interface Station {
  id: string; name: string; lat: number; lon: number; baseArrivalsPerMinute: number; platformCapacity?: number | null;
}
export interface Line {
  id: string; name: string; color: string; stationIds: string[]; runSeconds: number[];
  trainCount: number; trainCapacity: number; spareTrains: number;
}
export interface MetroNetwork {
  city: string; stations: Station[]; lines: Line[]; odWeights?: Record<string, Record<string, number>>;
}

export type TrainPhase = "DWELLING" | "HELD" | "WAITING_SIGNAL" | "RUNNING";
export interface TrainView {
  id: string; lineId: string; fromStationId: string; toStationId: string; progress: number;
  lat: number; lon: number; phase: TrainPhase; delaySeconds: number; load: number; capacity: number; failed: boolean;
}
export interface QueueView { lineId: string; towardsStationId: string; waiting: number }
export interface StationView {
  id: string; waiting: number; predictedWaiting15m: number; overloadRisk: "LOW" | "MEDIUM" | "HIGH";
  heldAtEntrance: number; leftBehind: number; queues: QueueView[]; closed: boolean;
}
export type ClosureResponse = "WAIT" | "TURN_BACK";
export interface ClosedSectionView {
  lineId: string; fromStationId: string; toStationId: string; response: ClosureResponse; remainingSeconds: number;
}
export type IncidentKind = "TRAIN_DELAY" | "BREAKDOWN" | "SECTION_CLOSURE" | "STATION_CLOSURE";
export interface ActiveIncidentView {
  kind: IncidentKind; subjectId: string; description: string; startedAt: number; clearsAt: number;
  remainingSeconds: number;
}
export interface SimEvent { simTime: number; type: string; subjectId: string; message: string }
export interface Snapshot {
  simTime: number; clock: string; strategy: string; trains: TrainView[]; stations: StationView[];
  recentEvents: SimEvent[];
  closedSections: ClosedSectionView[];
  activeIncidents: ActiveIncidentView[];
  metrics: {
    averageDelaySeconds: number; maxDelaySeconds: number; totalWaiting: number; passengersServed: number;
    passengersDelivered: number; transfers: number; passengersLeftBehind: number; passengersHeldAtEntrance: number;
    headwayCoefficientOfVariation: number; trainsInService: number; trainsInDepot: number;
    /** Stops run through under SKIP_STOP, and passengers who had to ride back to a skipped station. */
    stopsSkipped: number; passengersCarriedPast: number;
  };
}
export interface ShortTurn {
  trainId: string; lineId: string; stationId: string; simTime: number; latenessSeconds: number;
}
export interface SkippedStop {
  trainId: string; lineId: string; stationId: string; simTime: number; latenessSeconds: number;
  passengersCarriedPast: number;
}
export interface StrategyOutcome {
  strategy: string; trainsAffected: number; maxTrainDelaySeconds: number; totalExtraPassengerWaitMinutes: number;
  totalPassengerWaitMinutes: number; totalExtraRideMinutes: number; totalExtraTravelMinutes: number;
  totalPassengerTravelMinutes: number; headwayCvAtHorizon: number;
  /** Seconds until headways were back to baseline regularity; null if still disrupted at the horizon. */
  headwayRecoverySeconds: number | null; peakHeadwayCvExcess: number;
  /** Trains the strategy reversed early during the incident run, oldest first. */
  shortTurns: ShortTurn[];
  /** Stations late trains ran through during the incident run, oldest first. */
  skippedStops: SkippedStop[];
  trains: { trainId: string; extraDelaySeconds: number }[];
  stations: { stationId: string; extraWaitMinutes: number; peakWaitingDelta: number }[];
}
export interface DelayImpactReport {
  trainId: string; injectedDelaySeconds: number; horizonSeconds: number; outcomes: StrategyOutcome[]; bestStrategy: string;
}
export type ScenarioKind = "DELAY" | "BREAKDOWN" | "SECTION_CLOSURE" | "STATION_CLOSURE";
/** An incident injected by a what-if analysis or benchmark; only the fields its kind needs are present. */
export interface IncidentScenario {
  kind: ScenarioKind; seconds: number; trainId?: string; lineId?: string; fromStationId?: string;
  toStationId?: string; response?: ClosureResponse; stationId?: string;
}
export interface IncidentImpactReport {
  incident: IncidentScenario; horizonSeconds: number; outcomes: StrategyOutcome[]; bestStrategy: string;
}
/** KPIs for one closed minute of simulated time, as aggregated from the telemetry stream (`GET /api/kpis`). */
export interface MinuteKpi {
  /** floor(simTime / 60) */
  minute: number;
  /** Start of the minute on the simulated clock, HH:mm. */
  clock: string;
  trainsReporting: number; averageDelaySeconds: number; maxDelaySeconds: number; averageLoadFactor: number;
  /** Passengers waiting on platforms network-wide: mean and peak over the minute. */
  averageWaiting: number; maxWaiting: number;
  averageHeadwayCv: number; delayAlerts: number; incidents: number;
}
/** One row of a station departure board (`GET /api/stations/{id}/departures`); times in simulated seconds. */
export interface Departure {
  trainId: string; lineId: string;
  /** Terminal the train is heading for after this departure. */
  towardsStationId: string;
  plannedTime: number; plannedClock: string; expectedTime: number;
  /** expectedTime - plannedTime; negative when running early. */
  delaySeconds: number;
}
export type RankingMetric = "WAIT_PER_BOARDING" | "TRAVEL_TIME";
/** One strategy's result over all benchmark scenarios; `rank` 1 is best by the report's metric. */
export interface StrategyRanking {
  rank: number; strategy: string; meanWaitSeconds: number; p95WaitSeconds: number;
  meanTravelMinutes: number; p95TravelMinutes: number;
  /** Mean headway recovery over the scenarios recovered from; null if none recovered within the horizon. */
  meanRecoverySeconds: number | null; unrecoveredScenarios: number;
  meanShortTurns: number; shortTurnsByStation: Record<string, number>;
  meanStopsSkipped: number; meanPassengersCarriedPast: number; stopsSkippedByStation: Record<string, number>;
  waitSecondsPerScenario: number[];
}
export interface BenchmarkReport {
  seed: number; horizonSeconds: number; metric: RankingMetric; scenarios: IncidentScenario[];
  rankings: StrategyRanking[];
}
export type BenchmarkJobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";
/** A background benchmark run; `report` is set once SUCCEEDED, `error` once FAILED. Times are ISO-8601. */
export interface BenchmarkJob {
  id: string; status: BenchmarkJobStatus; completedRuns: number; totalRuns: number; progress: number;
  submittedAt: string; startedAt: string | null; finishedAt: string | null;
  report: BenchmarkReport | null; error: string | null;
}
export interface BenchmarkRequest {
  scenarios: number; seed: number; horizonSeconds: number; kinds: ScenarioKind[]; metric: RankingMetric;
}
