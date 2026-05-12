# Bus Tracker

A Spring Boot service that tells you when the next buses will arrive at a chosen Irish bus stop, combining the official **GTFS static schedule** with the **GTFS-Realtime** feed published by [Transport for Ireland](https://www.transportforireland.ie/) (TFI), and optionally pushes [ntfy.sh](https://ntfy.sh) notifications when a bus is approaching.

## What it does

- Downloads the static GTFS dataset on first boot and indexes it for a single configured stop.
- Polls the TFI GTFS-Realtime `TripUpdates` feed every 30 seconds (06:00–23:59 Europe/Dublin) and merges live delays / arrival times onto the scheduled trips.
- Detects unscheduled (added) trips reported only by the realtime feed and inserts them into the result.
- Exposes a single JSON endpoint listing the upcoming buses at the configured stop, sorted by actual (or scheduled) arrival time.
- Pushes a notification to a configured ntfy.sh topic whenever a bus is within a configurable minute threshold.

## Why it's useful

- **No public TFI endpoint** returns "next buses at stop X" directly — you have to join static GTFS with the realtime feed yourself. This service does that for you.
- Falls back gracefully to the static schedule when the realtime API is unavailable.
- Caches results so the public endpoint is cheap to hit from a frontend or smart display.
- Bootstraps its own data: the static GTFS feed is fetched and unzipped on first run, so you don't ship a 500 MB blob with your build.

## Tech stack

- Java 21
- Spring Boot 4.0.5 (`spring-boot-starter-web`)
- [`org.mobilitydata:gtfs-realtime-bindings`](https://github.com/MobilityData/gtfs-realtime-bindings) for parsing the protobuf feed
- Gradle (wrapper included)
- Docker (multi-stage build) for packaging
- Fly.io for hosting (optional — any container host works)

## Project layout

```
src/main/java/ie/bustracker/app/
├── BusTrackerApplication.java         # Spring Boot entry point (@EnableScheduling)
├── controllers/BusController.java     # GET /buses -> cached list
├── models/UpcomingBus.java            # tripId, routeName, scheduledTime, actualTime
├── config/
│   ├── GtfsBootstrap.java             # downloads + unzips the GTFS static feed on first boot
│   └── NotificationProperties.java    # @ConfigurationProperties for bus-tracker.notifications
└── services/
    ├── GtfsStaticService.java         # loads the static GTFS .txt files at startup
    ├── RealTimeService.java           # calls TFI realtime API, merges delays
    ├── BusPollingScheduler.java       # @Scheduled poller, 30s cadence
    └── NotificationService.java       # pushes ntfy.sh notifications

src/main/resources/
├── application.yml                    # non-secret config + ${ENV} placeholders for secrets
└── application-local.yml              # gitignored; secret values for local dev (loaded via 'local' profile)

Dockerfile                             # multi-stage build: JDK build stage -> slim JRE runtime
.dockerignore                          # keeps secrets and bulk data out of the image
fly.toml                               # Fly.io app config (region, volume, http_service)
```

## Getting started

### Prerequisites

- JDK 21
- A TFI API key — register at the [NTA developer portal](https://developer.nationaltransport.ie/) to get one.
- (Optional) An [ntfy.sh](https://ntfy.sh) topic URL if you want push notifications.

### Configure

Two values are treated as secrets and supplied via environment variables (or a local profile YAML during development):

| Property | Env var | Purpose |
|---|---|---|
| `bus-tracker.api-key` | `TFI_API_KEY` | TFI `x-api-key` header for the realtime feed |
| `bus-tracker.notifications.ntfy-url` | `NTFY_URL` | ntfy.sh topic URL to publish to |

For **local development**, drop your secrets into a gitignored `src/main/resources/application-local.yml`:

```yaml
bus-tracker:
    api-key: YOUR_TFI_API_KEY
    notifications:
        ntfy-url: https://ntfy.sh/your-topic
```

`./gradlew bootRun` automatically activates the `local` Spring profile (see [build.gradle](build.gradle)), so this file is loaded with no further setup.

For **production**, set the env vars instead — e.g. on Fly.io:

```bash
fly secrets set TFI_API_KEY=... NTFY_URL=https://ntfy.sh/your-topic
```

Other (non-secret) settings live in [application.yml](src/main/resources/application.yml):

```yaml
bus-tracker:
    stop-id: "8380B246441"            # the GTFS stop_id you want to track
    gtfs:
        dir: src/main/resources/gtfs                                              # where to read/write GTFS CSVs
        zip-url: https://www.transportforireland.ie/transitData/Data/GTFS_Realtime.zip   # static GTFS source
    notifications:
        enabled: true
        minutes-threshold: 15         # notify when a bus is within this many minutes
        routes: []                    # empty = all routes; otherwise a list of route short names
```

To find your `stop_id`, run the app once so `GtfsBootstrap` populates the data directory, then `grep` for your stop name in `stops.txt`.

### Run

```bash
./gradlew bootRun
```

On first run, `GtfsBootstrap` notices the GTFS directory is empty, downloads the ~98 MB TFI static zip, extracts the CSVs (~500 MB on disk), and the app starts on `http://localhost:8080`. Subsequent runs skip the download.

### Use

```bash
curl http://localhost:8080/buses
```

Example response:

```json
[
  {
    "tripId": "3811_1234",
    "routeName": "220",
    "scheduledTime": "14:32",
    "actualTime": "14:35"
  },
  {
    "tripId": null,
    "routeName": "208",
    "scheduledTime": null,
    "actualTime": "14:41"
  }
]
```

`actualTime` is `null` when the realtime feed has no update for that trip — display `scheduledTime` in that case. All times are truncated to minute precision at parse time.

## How it works

1. **Bootstrap (first boot only)** — [GtfsBootstrap](src/main/java/ie/bustracker/app/config/GtfsBootstrap.java) checks `bus-tracker.gtfs.dir`. If the required CSVs are missing, it downloads `GTFS_Realtime.zip` from TFI and extracts it. On every subsequent boot it logs "already present" and returns immediately.
2. **Static load** — [GtfsStaticService](src/main/java/ie/bustracker/app/services/GtfsStaticService.java) parses the GTFS `.txt` files and builds in-memory maps keyed by `tripId` for the configured `stop-id`. Ordering is enforced via `@DependsOn("gtfsBootstrap")`.
3. **Polling** — [BusPollingScheduler](src/main/java/ie/bustracker/app/services/BusPollingScheduler.java) refreshes the cache every 30 seconds (cron `0/30 * 6-23 * * *`, Europe/Dublin).
4. **Live merge** — [RealTimeService](src/main/java/ie/bustracker/app/services/RealTimeService.java) fetches `TripUpdates` from `https://api.nationaltransport.ie/gtfsr/v2/TripUpdates`, applies delays / absolute arrival times to scheduled trips, and appends any added trips.
5. **Notify** — [NotificationService](src/main/java/ie/bustracker/app/services/NotificationService.java) iterates upcoming buses; for each within `minutes-threshold` minutes (and matching the optional route filter), it POSTs to the configured ntfy topic. Deduplicated by `tripId` per process lifetime.
6. **Serving** — [BusController](src/main/java/ie/bustracker/app/controllers/BusController.java) returns the cached list at `GET /buses`.

## Build

```bash
./gradlew build       # compiles + runs tests
./gradlew test        # tests only
./gradlew bootJar     # produces an executable jar in build/libs/
```

## Run in Docker

The included [Dockerfile](Dockerfile) does a multi-stage build (JDK + Gradle → slim JRE) and produces a ~430 MB image. The GTFS data is **not** bundled — `GtfsBootstrap` fetches it at runtime into a writable directory (`/data/gtfs` inside the container).

```bash
docker build -t bus-tracker .
docker run --rm -p 8080:8080 \
  -e TFI_API_KEY=... \
  -e NTFY_URL=https://ntfy.sh/your-topic \
  -v $(pwd)/.local-gtfs:/data/gtfs \
  bus-tracker
```

The bind mount on `/data/gtfs` lets the downloaded CSVs survive `docker rm`, so you don't re-download 98 MB on every run.

## Deploy to Fly.io

[fly.toml](fly.toml) is pre-configured for a single VM in the `lhr` region with a 1 GB persistent volume mounted at `/data` (where `GtfsBootstrap` writes). One-time setup, assuming you already have `flyctl` installed and an account:

```bash
fly launch --no-deploy --primary-region lhr   # generates/updates fly.toml; pick an app name
fly volumes create gtfs_data --size 1 --region lhr
fly secrets set TFI_API_KEY=... NTFY_URL=https://ntfy.sh/your-topic
fly deploy
```

Subsequent code changes deploy with a single command:

```bash
fly deploy
```

The persistent volume retains the GTFS data across deploys, so the 98 MB download only happens once. `min_machines_running = 1` is set in `fly.toml` so the always-on scheduler keeps firing.

## Where to get help

- **TFI / NTA API questions:** [developer.nationaltransport.ie](https://developer.nationaltransport.ie/)
- **GTFS spec:** [gtfs.org](https://gtfs.org/)
- **GTFS-Realtime reference:** [gtfs.org/realtime/reference](https://gtfs.org/realtime/reference/)
- **Spring Boot docs:** [docs.spring.io/spring-boot](https://docs.spring.io/spring-boot/)
- **Fly.io docs:** [fly.io/docs](https://fly.io/docs/)
- **ntfy docs:** [docs.ntfy.sh](https://docs.ntfy.sh/)
- **Issues with this project:** open an issue on the repository.
