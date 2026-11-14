# MetroMind

**A digital twin of a city metro system.** MetroMind simulates trains, stations and passenger flows in real time,
detects delays, predicts overcrowded stations and answers the key operational question:

> *Train RED-3 is stuck for 4 minutes. What happens to every other train and station in the next 30 minutes,
> and which dispatching strategy limits the damage best?*

![MetroMind dashboard](docs/screenshot.png)

## What it does

| Feature | How |
|---|---|
| Virtual trains, stations, lines | Network loaded from JSON, persisted to PostGIS (`GEOGRAPHY(POINT)`, `GEOMETRY(LINESTRING)`) |
| Train movement | Discrete-time engine, 1 s step, fixed-block signalling (segment + next platform) |
| Passenger flow | Fluid demand model with morning/evening peaks; origin–destination matrix (gravity model or explicit weights); fastest-route assignment with transfers at interchanges; one queue per station, line and direction, tracked by destination; crowding-dependent dwell times |
| Delay detection | Each train tracks lateness against its nominal schedule; alerts at 120 s, recovery at 60 s |
| Platform crowding | Optional `platformCapacity` per station: when platforms are full, new passengers queue at the entrance; passengers left behind by full trains are counted |
| Overload prediction | 15-minute forecast per station, summed over its directional queues: queue + inflow (new trips and recent transfers) − share of free space of trains calling in that direction, after their passengers for this station alight |
| Dynamic headway control | Pluggable `HeadwayStrategy`: `NO_CONTROL`, `FORWARD_HOLDING`, `BALANCED` (two-way headway balancing) |
| Incidents & failures | Inject delays or breakdowns on any train at runtime; close a track section (signal failure) with trains waiting or turning back; close a station |
| **Delay propagation (what-if)** | Fork the live engine, run *baseline* vs *incident* for every strategy, diff arrivals and passenger wait |

### The delay-propagation mechanic

The engine is fully deterministic, so it can be copied. `DelayPropagationAnalyzer` takes a snapshot of the live
network, makes two copies per strategy, injects the delay into one, simulates both for the horizon and reports:

- extra delay per train (how much later it reaches each stop than in the baseline);
- extra passenger-minutes of waiting per station, and peak queue growth;
- extra passenger-minutes spent on board, and the total (wait + on board);
- headway regularity (coefficient of variation) at the end of the horizon;
- the strategy with the lowest total passenger travel time (platform wait + time on board).

**Can a delay reduce waiting?** Platform waiting alone, yes, and that is why it is not the yardstick. When a
train is late its followers catch up and pick passengers up sooner, so people move from the platform onto
crowded trains, and passengers on the late train wait aboard instead of on a platform. On the seed network a
one-minute delay could show ~375 passenger-minutes *less* platform waiting while adding ~390 minutes on board.
Trains also used to start snapped to platforms, with uneven gaps, so a delay could accidentally even out the
headways; they now start evenly spaced. What remains under `NO_CONTROL` are small net gains (well under 1 % of
total travel time) when a delay happens to rebalance load between neighbouring trains or shifts transfers at
interchanges: real effects of an uncontrolled line, not measurement errors.

```http
POST /api/analysis/delay-impact?horizonSeconds=1800
{ "trainId": "RED-3", "seconds": 240 }
```

## Architecture

```
            ┌──────────────────────── Spring Boot backend ────────────────────────┐
            │  SimulationEngine ──► SimulationService (@Scheduled tick)            │
 Next.js ◄──┤        │                      │                                     │
 (STOMP/WS) │  DelayPropagationAnalyzer     ├──► WebSocket  /topic/state          │
            │  HeadwayStrategy              ├──► Kafka      metromind.train-positions, metromind.events
 REST ─────►│  REST /api/*                  ├──► Redis      metromind:snapshot, metromind:station-waiting
            │                               └──► PostGIS    station, line, incident │
            └──────────────────────────────────────────────────────────────────────┘
```

**Stack:** Java 21 · Spring Boot 3.5 · Spring Kafka · Spring Data Redis · PostgreSQL 16 + PostGIS · Flyway ·
STOMP over WebSocket · Next.js 15 / React 19 / TypeScript · Docker Compose · GitHub Actions.

## Running

Everything in Docker:

```bash
docker compose up --build
# UI: http://localhost:3000   API: http://localhost:8080/api/state
```

Backend without any infrastructure (simulation + WebSocket only):

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=standalone
cd frontend && npm install && npm run dev
```

Infra in Docker, backend from the IDE: `docker compose up -d postgres redis kafka`, then run
`MetroMindApplication` with the default profile.

## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/network` | Stations and lines |
| GET | `/api/state` | Latest simulation snapshot |
| GET | `/api/stations/{id}/departures?horizonSeconds=1800` | Departure board: planned vs expected departures |
| GET | `/api/strategies` | Available headway strategies |
| PUT | `/api/strategy` | `{ "strategy": "BALANCED" }` |
| POST | `/api/incidents/delay` | `{ "trainId": "RED-3", "seconds": 240 }` |
| POST | `/api/incidents/breakdown` | `{ "trainId": "BLUE-2", "seconds": 600 }` |
| POST | `/api/incidents/section-closure` | `{ "lineId": "RED", "fromStationId": "R4", "toStationId": "R5", "seconds": 900, "response": "TURN_BACK" }` |
| POST | `/api/incidents/station-closure` | `{ "stationId": "R3", "seconds": 1200 }` |
| POST | `/api/analysis/delay-impact` | What-if analysis across strategies |
| GET | `/api/incidents?limit=50` | Incident history, newest first |
| GET | `/api/incidents/analyses?limit=10` | Past what-if analyses with their full reports, newest first |
| POST | `/api/simulation/pause` / `resume` | Pause or resume the clock |

WebSocket: connect to `ws://localhost:8080/ws` (STOMP) and subscribe to `/topic/state`.

Each station in a snapshot carries `queues`: waiting passengers per line and direction, e.g.
`{ "lineId": "RED", "towardsStationId": "R7", "waiting": 42.0 }`. A train only boards the queue for the
direction it is about to travel in; terminals have a single queue towards the other end of the line.

### Passenger demand and routing

Every passenger has an origin and a destination. By default destinations follow a gravity model: from any
station, a destination attracts trips in proportion to its own ridership (`baseArrivalsPerMinute`). A network
file may override this per origin with relative weights (destinations not listed get none):

```json
"odWeights": { "R1": { "R3": 3, "B6": 1 } }
```

Passengers take the fastest route: each segment costs its nominal run time plus a dwell, boarding a line costs
half its headway and a change of line adds a 120 s walk. They leave the train at their destination, or at an
interchange where they join the queue for their next line. Snapshot `metrics` report `passengersServed`
(boardings, one per line ridden), `passengersDelivered` (completed journeys) and `transfers` (line changes).

### Timetable and departure boards

Trains start evenly spaced around each line's loop, and that start defines their timetable: planned arrivals
advance by the nominal run time plus a 25 s dwell. Delay alerts compare each arrival with this timetable. A train
that reaches a terminal early (short dwells off-peak) waits there for its planned departure, so earliness does not
carry into the next trip; late trains leave as soon as they can.

`GET /api/stations/R3/departures` lists the trains leaving a station within the horizon, soonest first:

```json
{ "trainId": "BLUE-2", "lineId": "BLUE", "towardsStationId": "B6", "plannedTime": 29130.0,
  "plannedClock": "08:05:30", "expectedTime": 29372.0, "delaySeconds": 242.0 }
```

The expected time is estimated from the train's position, nominal run times and any blockage, so a negative
`delaySeconds` means the train is running early.

### Platform capacity and crowding

A station may declare how many passengers its platforms hold, all queues together:

```json
{ "id": "R3", "name": "Central", "lat": 40.375, "lon": 49.82, "baseArrivalsPerMinute": 12, "platformCapacity": 450 }
```

While the platforms are full, new passengers wait at the entrance and are let in as trains clear the queues.
Passengers changing lines are always let through, so an interchange can briefly exceed its limit. Time at the
entrance counts as waiting (it is included in delay-impact analysis), and the overload forecast adds the
entrance queue on top of the platform forecast. Stations without the field have unlimited platforms.

Snapshots report per station `heldAtEntrance` and `leftBehind` (passengers who could not board a full train in
their direction, counted once per train missed), and network-wide `passengersHeldAtEntrance` and
`passengersLeftBehind`.

### Track section closures

A signal or track failure closes the track between two adjacent stations of a line, in both directions, for a
given time. A train already inside the section finishes its run; no other train may enter it. The `response`
decides what trains do on reaching the closure:

- `WAIT` (default): the train stays at the platform before the section with a red signal. Trains behind it queue
  up station by station, as fixed-block signalling would make them.
- `TURN_BACK`: the train reverses at the last open station and serves the rest of the line in the other
  direction. Everyone still on board gets off there and waits on the platform for the line to reopen, and nobody
  boards a train that is about to turn. A train cannot turn at a terminal, or while the opposite platform is
  occupied; it waits until it can.

Snapshots list active closures under `closedSections`, with the time left before each section reopens. The
departure board and the overload forecast do not yet account for closures.

### Station closures

A closed station keeps its doors shut: trains run through it without stopping or being held (a closed terminal
still turns trains round). Its departure board is empty and its station view has `closed: true`.

- Passengers on the platforms or at the entrance when it closes walk to the neighbouring stations (adjacent on
  any line, split evenly) and queue at their entrances. New passengers arriving while it is closed do the same.
  In the fluid model they then travel like the neighbour's own passengers.
- Passengers on board who were heading for the closed station get off at the first open station after it and
  count as delivered there.
- Passengers who planned to change lines at a closed interchange stay aboard to the next open station and are
  re-routed from there using the normal route table, which may still take them through the closed station.

### Incident history

Every incident injected through the API (delay, breakdown, track or station closure) is recorded with the
simulation time, its subject (train, line or station), its duration and, for track closures, the section and
how trains react (`"detail": "R4-R5, TURN_BACK"`). Each what-if analysis is recorded too, with its full report.
With persistence enabled they are stored in PostgreSQL (`incident` and `incident_analysis`, the report as
JSONB) and survive restarts; in the `standalone` profile the last 500 incidents and 50 analyses are kept in
memory.

## Project layout

```
backend/   Spring Boot service (sim engine, analysis, adapters)
frontend/  Next.js dashboard
docs/      Roadmap and design notes
```

See [docs/ROADMAP.md](docs/ROADMAP.md) for what is planned next.
