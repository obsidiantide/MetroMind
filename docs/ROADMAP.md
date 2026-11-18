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
- [x] Investigate why a delay can *reduce* total waiting under NO_CONTROL in some runs; fix or document
- [x] Platform capacity limits and "passengers left behind" metric
- [x] Scheduled timetable (planned departures) and lateness vs timetable instead of vs nominal run times
- [x] Circular lines (trains run round a ring without reversing), so Moscow-like networks with a ring can be loaded
- [x] Branching lines (a service pattern that splits at a junction, like Baku's Red line towards Bakmil). The
      engine keys platforms, signalling blocks and route tables by one line's loop of stops, so this lands in steps:
  - [x] Service patterns: a line's trains may each follow one of several station sequences sharing the trunk
        (loop stops, run times and the timetable per pattern; existing lines are a single pattern)
  - [x] Shared trunk signalling: blocks keyed by track section rather than by loop stop, so trains of different
        patterns cannot occupy the same section or platform
  - [x] Trunk platforms shared by patterns: passengers for a trunk station board any train, passengers for a
        branch only trains of that branch (route planner aware of patterns)
  - [x] Network files: `branches` on a line (junction station, branch stations, share of trains), JSON and GeoJSON
  - [x] Bundle the Bakmil branch on the Baku network and draw branches on the map
- [x] Cross-platform interchange: changing between the two directions of a ring (or lines sharing an island
      platform) costs no walk, unlike the default 120 s transfer
- [x] Configurable timing points per line (today: terminals, or a ring's first station) for holding to timetable
- [x] Headway control on a shared trunk: regulate the combined headway of a branched line's patterns where they
      run together (today each pattern is regulated on its own, so trains of two patterns can bunch on the trunk)
- [x] Overload forecast on branched lines: count only the free space of trains whose pattern serves each waiting
      passenger's destination (today every train of the line calling at the platform relieves the queue)

### Incidents
- [x] Segment / signal failure (track section closed, trains turn back or wait)
- [x] Station closure (trains skip the station, demand redistributes to neighbours)
- [x] Persist incidents and their analysis results to the `incident` table; `GET /api/incidents` history
- [x] Automatic incident clearing timeline in the UI

### Optimisation and comparison
- [x] Strategy: adaptive dispatching that adds / withdraws trains from depot when overload is predicted
- [x] Strategy: skip-stop / short-turning for recovery (short-turning; skip-stop tracked below)
- [x] Strategy benchmark endpoint: run N random incident scenarios and rank strategies (mean, p95 passenger wait)
- [x] Recovery-time metric: how long until headways return to normal after an incident
- [x] Dynamic headway target that tracks predicted demand (`DEMAND_HOLDING`)
- [x] Strategy: skip-stop (a late train runs through low-demand stations; their passengers ride on to the next stop)
- [x] Benchmark scenarios with track-section and station closures, not only train delays and breakdowns
- [x] Short-turn report: count and location of short turns in what-if and benchmark results
- [x] Skipped-stop report: stops skipped, where, and passengers carried past, in what-if and benchmark results
- [x] What-if analysis for track-section and station closures, not only train delays
- [x] Benchmark ranking metric selectable: platform wait per boarding or total passenger travel time
- [x] Strategy combining short-turning and skip-stop, with short turns taking precedence for very late trains
- [x] Adaptive dispatching on branched lines: send a spare train to the pattern with the worst overload forecast
      (today spare trains always join the main route)

### Platform
- [x] Kafka consumer service: aggregate telemetry into per-minute KPIs stored in PostgreSQL
- [x] Redis-backed snapshot replay / time travel (store last N minutes)
- [x] PostGIS queries: nearest station to a point, stations within radius (`ST_DWithin`) exposed via API
- [x] OpenAPI (springdoc) documentation
- [x] Integration tests with Testcontainers (PostGIS, Kafka, Redis)
- [x] Micrometer metrics for simulation tick time and analysis duration
- [x] Run benchmarks as background jobs (`202 Accepted` + job id, poll for progress) so large runs do not hold a request
- [x] KPI aggregation robust to lag between topics: track a watermark per telemetry topic and close a minute only
      when every topic has passed it (today a backlog on positions lets metrics close minutes early and drops samples)
- [x] Cancel a queued or running benchmark job (`DELETE /api/analysis/benchmark/jobs/{id}`)
- [x] Persist finished benchmark jobs in PostgreSQL so results survive restarts
- [ ] Service patterns in `GET /api/network` (stations, run times and trains of each) and the pattern of each train
      in the snapshot

### Frontend
- [x] Charts: delay and waiting over time
- [x] Click a train or station for a detail drawer
- [x] Visualise what-if result on the map (heat of extra wait per station)
- [x] Strategy benchmark page
- [x] Load real city networks from GeoJSON (Baku bundled; a Moscow-like network needs ring lines, tracked below)
- [x] Component tests (Vitest + Testing Library) for the charts, detail drawer and benchmark page
- [ ] Browser smoke test in CI: dashboard renders the map and opens a station drawer against the standalone backend
- [x] Export a benchmark report as CSV
- [ ] Line legend with ring direction arrows on the map
- [ ] Train markers and departure boards name the branch a train is bound for (e.g. "to Bakmil")
