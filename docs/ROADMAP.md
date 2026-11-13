# Roadmap

Work top to bottom. Each item should land as one focused pull request with tests.
Mark items `[x]` when merged.

## Done

- [x] Network model (stations, lines, loop topology) loaded from JSON
- [x] Deterministic simulation engine with fixed-block signalling
- [x] Fluid passenger demand with time-of-day peaks, boarding/alighting, crowding-dependent dwell
- [x] Delay detection events
- [x] 15-minute station overload prediction
- [x] Headway strategies: NO_CONTROL, FORWARD_HOLDING, BALANCED
- [x] Incidents: delay, breakdown
- [x] Counterfactual delay-propagation analysis across strategies
- [x] Kafka telemetry, Redis state cache, PostGIS topology, STOMP WebSocket feed
- [x] Next.js live dashboard with map, incident panel and what-if table
- [x] Docker Compose, CI
- [x] Per-direction passenger queues (one queue per station, line and direction of travel)

## Next

### Simulation fidelity
- [x] Origin–destination matrix instead of a fixed alighting fraction; transfers between lines at interchanges
- [ ] Investigate why a delay can *reduce* total waiting under NO_CONTROL in some runs; fix or document
- [ ] Platform capacity limits and "passengers left behind" metric
- [ ] Scheduled timetable (planned departures) and lateness vs timetable instead of vs nominal run times

### Incidents
- [ ] Segment / signal failure (track section closed, trains turn back or wait)
- [ ] Station closure (trains skip the station, demand redistributes to neighbours)
- [ ] Persist incidents and their analysis results to the `incident` table; `GET /api/incidents` history
- [ ] Automatic incident clearing timeline in the UI

### Optimisation and comparison
- [ ] Strategy: adaptive dispatching that adds / withdraws trains from depot when overload is predicted
- [ ] Strategy: skip-stop / short-turning for recovery
- [ ] Strategy benchmark endpoint: run N random incident scenarios and rank strategies (mean, p95 passenger wait)
- [ ] Recovery-time metric: how long until headways return to normal after an incident
- [ ] Dynamic headway target that tracks predicted demand

### Platform
- [ ] Kafka consumer service: aggregate telemetry into per-minute KPIs stored in PostgreSQL
- [ ] Redis-backed snapshot replay / time travel (store last N minutes)
- [ ] PostGIS queries: nearest station to a point, stations within radius (`ST_DWithin`) exposed via API
- [ ] OpenAPI (springdoc) documentation
- [ ] Integration tests with Testcontainers (PostGIS, Kafka, Redis)
- [ ] Micrometer metrics for simulation tick time and analysis duration

### Frontend
- [ ] Charts: delay and waiting over time
- [ ] Click a train or station for a detail drawer
- [ ] Visualise what-if result on the map (heat of extra wait per station)
- [ ] Strategy benchmark page
- [ ] Load real city networks (Baku, Moscow-like) from GeoJSON
