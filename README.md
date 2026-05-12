# Bus Tracker

A Spring Boot service that tells you when the next buses will arrive at a chosen Irish bus stop, combining the official **GTFS static schedule** with the **GTFS-Realtime** feed published by [Transport for Ireland](https://www.transportforireland.ie/) (TFI).

## What it does

- Loads the static GTFS dataset (routes, trips, stop times, calendar) at startup and indexes it for a single configured stop.
- Polls the TFI GTFS-Realtime `TripUpdates` feed every 30 seconds (06:00–23:59 Europe/Dublin) and merges live delays / arrival times onto the scheduled trips.
- Detects unscheduled (added) trips reported only by the realtime feed and inserts them into the result.
- Exposes a single JSON endpoint listing the upcoming buses at the configured stop, sorted by actual (or scheduled) arrival time.

## Why it's useful

- **No public TFI endpoint** returns "next buses at stop X" directly — you have to join static GTFS with the realtime feed yourself. This service does that for you.
- Falls back gracefully to the static schedule when the realtime API is unavailable.
- Caches results so the public endpoint is cheap to hit from a frontend or smart display.

## Tech stack

- Java 21
- Spring Boot 4.0.5 (`spring-boot-starter-web`)
- [`org.mobilitydata:gtfs-realtime-bindings`](https://github.com/MobilityData/gtfs-realtime-bindings) for parsing the protobuf feed
- Gradle (wrapper included)

## Project layout

```
src/main/java/ie/bustracker/app/
├── BusTrackerApplication.java        # Spring Boot entry point (@EnableScheduling)
├── controllers/BusController.java    # GET /buses -> cached list
├── models/UpcomingBus.java           # tripId, routeName, scheduledTime, actualTime
└── services/
    ├── GtfsStaticService.java        # loads static GTFS .txt files at startup
    ├── RealTimeService.java          # calls TFI realtime API, merges delays
    └── BusPollingScheduler.java      # @Scheduled poller, 30s cadence

src/main/resources/
├── application.yml                   # api-key + stop-id config
└── gtfs/                             # static GTFS feed (routes.txt, trips.txt, …)
```

## Getting started

### Prerequisites

- JDK 21
- A TFI API key — register at the [NTA developer portal](https://developer.nationaltransport.ie/) to get one.
- A GTFS static feed for Ireland — download from [transportforireland.ie/transitData](https://www.transportforireland.ie/transitData/PT_Data.html) and unzip the `.txt` files into [src/main/resources/gtfs/](src/main/resources/gtfs/).

### Configure

Edit [src/main/resources/application.yml](src/main/resources/application.yml):

```yaml
bus-tracker:
    api-key: YOUR_TFI_API_KEY
    stop-id: "8380B246441"   # the GTFS stop_id you want to track
```

Find the `stop_id` you want by searching `src/main/resources/gtfs/stops.txt` for your stop name.

> For real deployments, prefer environment variables over committing the key:
> ```yaml
> bus-tracker:
>     api-key: ${TFI_API_KEY}
>     stop-id: ${TFI_STOP_ID}
> ```

### Run

```bash
./gradlew bootRun
```

The application starts on `http://localhost:8080`.

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
    "scheduledTime": "14:32:00",
    "actualTime": "14:35:12"
  },
  {
    "tripId": null,
    "routeName": "208",
    "scheduledTime": null,
    "actualTime": "14:41:00"
  }
]
```

`actualTime` is `null` when the realtime feed has no update for that trip — display `scheduledTime` in that case.

## How it works

1. **Startup** — [GtfsStaticService](src/main/java/ie/bustracker/app/services/GtfsStaticService.java) parses the GTFS `.txt` files and builds in-memory maps keyed by `tripId` for the configured `stop-id`.
2. **Scheduling** — [BusPollingScheduler](src/main/java/ie/bustracker/app/services/BusPollingScheduler.java) refreshes the cache every 30 seconds (cron `0/30 * 6-23 * * *`, Europe/Dublin).
3. **Live merge** — [RealTimeService](src/main/java/ie/bustracker/app/services/RealTimeService.java) fetches `TripUpdates` from `https://api.nationaltransport.ie/gtfsr/v2/TripUpdates`, applies delays / absolute arrival times to scheduled trips, and appends any trips marked as added.
4. **Serving** — [BusController](src/main/java/ie/bustracker/app/controllers/BusController.java) returns the cached list at `GET /buses`.

## Build

```bash
./gradlew build       # compiles + runs tests
./gradlew test        # tests only
./gradlew bootJar     # produces an executable jar in build/libs/
```

## Where to get help

- **TFI / NTA API questions:** [developer.nationaltransport.ie](https://developer.nationaltransport.ie/)
- **GTFS spec:** [gtfs.org](https://gtfs.org/)
- **GTFS-Realtime reference:** [gtfs.org/realtime/reference](https://gtfs.org/realtime/reference/)
- **Spring Boot docs:** [docs.spring.io/spring-boot](https://docs.spring.io/spring-boot/)
- **Issues with this project:** open an issue on the repository.

