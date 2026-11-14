export interface Station { id: string; name: string; lat: number; lon: number; baseArrivalsPerMinute: number }
export interface Line {
  id: string; name: string; color: string; stationIds: string[]; runSeconds: number[];
  trainCount: number; trainCapacity: number;
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
  id: string; waiting: number; predictedWaiting15m: number; overloadRisk: "LOW" | "MEDIUM" | "HIGH"; queues: QueueView[];
}
export interface SimEvent { simTime: number; type: string; subjectId: string; message: string }
export interface Snapshot {
  simTime: number; clock: string; strategy: string; trains: TrainView[]; stations: StationView[];
  recentEvents: SimEvent[];
  metrics: {
    averageDelaySeconds: number; maxDelaySeconds: number; totalWaiting: number; passengersServed: number;
    passengersDelivered: number; transfers: number; headwayCoefficientOfVariation: number;
  };
}
export interface StrategyOutcome {
  strategy: string; trainsAffected: number; maxTrainDelaySeconds: number; totalExtraPassengerWaitMinutes: number;
  totalPassengerWaitMinutes: number; totalExtraRideMinutes: number; totalExtraTravelMinutes: number;
  totalPassengerTravelMinutes: number; headwayCvAtHorizon: number;
  trains: { trainId: string; extraDelaySeconds: number }[];
  stations: { stationId: string; extraWaitMinutes: number; peakWaitingDelta: number }[];
}
export interface DelayImpactReport {
  trainId: string; injectedDelaySeconds: number; horizonSeconds: number; outcomes: StrategyOutcome[]; bestStrategy: string;
}
