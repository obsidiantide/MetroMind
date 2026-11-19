export interface Station {
  id: string; name: string; lat: number; lon: number; baseArrivalsPerMinute: number; platformCapacity?: number | null;
}
export interface Line {
  id: string; name: string; color: string; stationIds: string[]; runSeconds: number[];
  trainCount: number; trainCapacity: number; spareTrains: number;
  /** Track alignment as [lon, lat] pairs from the first station to the last; empty when only stations are known. */
  path?: [number, number][];
  /** Trains circulate round the stations in order instead of reversing; runSeconds then also closes the ring. */
  ring?: boolean;
  /** Branches some of the line's trains run to instead of the end of the main route. */
  branches?: Branch[];
  /** Station sequences the line's trains follow: the main route, then one per branch (trunk plus branch). */
  patterns?: ServicePattern[];
  /** Time-of-day fleet plan, periods in order of their start; empty or absent when trainCount runs all day. */
  fleetPlan?: FleetPlanPeriod[];
}
/** From `from` (HH:mm) until the next period starts the line runs `trains` trains; the last period runs past midnight. */
export interface FleetPlanPeriod { from: string; trains: number }
/** One way a line's trains run; `id` is the line id for the main route, `line/branch-terminal` for a branch. */
export interface ServicePattern {
  id: string; lineId: string; stationIds: string[]; runSeconds: number[]; ring: boolean;
  timingStationIds: string[]; trainCount: number;
}
/** Past the junction, a share of the line's trains run to the branch stations instead. */
export interface Branch {
  junctionStationId: string;
  /** Branch stations in order away from the junction; the last is the branch terminal. */
  stationIds: string[];
  runSeconds: number[];
  share: number;
  /** Track alignment as [lon, lat] pairs from the junction to the branch terminal; empty when only stations are known. */
  path?: [number, number][];
  /**
   * Flat junction: seconds a train leaving for the branch occupies the crossing with the opposite main track, which
   * main-route trains running back into the junction must wait for. 0 or absent for a grade-separated junction.
   */
  crossingSeconds?: number;
}
export interface MetroNetwork {
  city: string; stations: Station[]; lines: Line[]; odWeights?: Record<string, Record<string, number>>;
}

export type TrainPhase = "DWELLING" | "HELD" | "WAITING_SIGNAL" | "RUNNING";
export interface TrainView {
  id: string; lineId: string;
  /** Service pattern the train follows (see Line.patterns); equals lineId on an unbranched line. */
  patternId: string;
  fromStationId: string; toStationId: string;
  /** Terminal the train is bound for on its current run (a branch train heading out: the branch terminal); on a ring, the next station. */
  towardsStationId: string;
  progress: number;
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
export type IncidentKind = "TRAIN_DELAY" | "BREAKDOWN" | "SECTION_CLOSURE" | "STATION_CLOSURE" | "DOOR_FAULT";
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
    /**
     * Per branched line id, headway CV of the combined service of its patterns on the shared trunk; the overall
     * CV judges each pattern on its own. Unbranched lines have no entry.
     */
    trunkHeadwayCv: Record<string, number>;
    /** Trains running empty between a depot and its line's first terminal; absent from older snapshots. */
    trainsOnDepotRuns?: number;
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
  /** Per branched line id, headway CV of the combined service on its shared trunk at the horizon. */
  trunkHeadwayCvAtHorizon: Record<string, number>;
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
export type ScenarioKind = "DELAY" | "BREAKDOWN" | "SECTION_CLOSURE" | "STATION_CLOSURE" | "DOOR_FAULT";
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
  /** Per branched line id, mean over the scenarios of the trunk headway CV at the horizon. */
  meanTrunkHeadwayCv: Record<string, number>;
  meanShortTurns: number; shortTurnsByStation: Record<string, number>;
  meanStopsSkipped: number; meanPassengersCarriedPast: number; stopsSkippedByStation: Record<string, number>;
  waitSecondsPerScenario: number[];
}
export interface BenchmarkReport {
  seed: number; horizonSeconds: number; metric: RankingMetric; scenarios: IncidentScenario[];
  rankings: StrategyRanking[];
  /** Spread of run times around nominal every run was drawn with (log-sd, 0.05 ≈ ±5 %); 0 or absent for nominal. */
  runTimeVariability?: number;
  /** Spread of dwells around the passenger-driven one every run was drawn with; 0 or absent for none. */
  dwellVariability?: number;
}
/** A strategy's standing over every benchmarked time of day: means of its per-period means. */
export interface DayRanking {
  rank: number; strategy: string; meanWaitSeconds: number; meanTravelMinutes: number;
  /** Start time (HH:mm) of the period where it ranked lowest, and that rank. */
  worstStartClock: string; worstRank: number;
}
/** The benchmark run from several times of day, each from a network warmed up to that time. */
export interface TimeOfDayReport {
  seed: number; horizonSeconds: number; metric: RankingMetric;
  periods: { startClock: string; report: BenchmarkReport }[];
  rankings: DayRanking[];
}
export type BenchmarkJobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
/**
 * A background benchmark run; `report` is set once SUCCEEDED, `error` once FAILED. A CANCELLED job keeps the runs it
 * completed but no report. Times are ISO-8601. `R` is the report: a strategy benchmark's, or a `TimeOfDayReport` for
 * a benchmark across times of day.
 */
export interface BenchmarkJob<R = BenchmarkReport> {
  id: string; status: BenchmarkJobStatus; completedRuns: number; totalRuns: number; progress: number;
  submittedAt: string; startedAt: string | null; finishedAt: string | null;
  report: R | null; error: string | null;
}
export type DayBenchmarkJob = BenchmarkJob<TimeOfDayReport>;
export interface BenchmarkRequest {
  scenarios: number; seed: number; horizonSeconds: number; kinds: ScenarioKind[]; metric: RankingMetric;
  /** Spread of run times (0–0.25); left out, the benchmark uses the live engine's. */
  runTimeVariability?: number;
  /** Spread of dwells (0–0.25); left out, the benchmark uses the live engine's. */
  dwellVariability?: number;
}
