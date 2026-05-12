package ie.bustracker.app.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import ie.bustracker.app.models.UpcomingBus;

@Service
@DependsOn("gtfsBootstrap")
public class GtfsStaticService {
    private static final Logger log = LoggerFactory.getLogger(GtfsStaticService.class);

    //*  GTFS data directory (filesystem; populated by bootstrap at runtime) *//
    @Value("${bus-tracker.gtfs.dir}")
    private String gtfsDirStr;

    private Path gtfsDir;

    // My bus stop 
    @Value("${bus-tracker.stop-id}")
  	private String stopId; 

    //* Hashmaps with data *//

    // tripId -> {serviceId, routeId}
    private Map<String, String[]> trips = new HashMap<>(); 

    // tripId -> scheduled arrival time at our stop
    private Map<String, LocalTime> stopTimes = new HashMap<>();

    // routeId -> route short name (e.g. "220")
    private Map<String, String> routes = new HashMap<>();

    // serviceId -> calendar row (days + date range)
    private Map<String, String[]> calendar = new HashMap<>();

    // serviceId -> set of exception dates with type
    private Map<String, Map<String, Integer>> calendarDates = new HashMap<>();  // optional for now

    // Populate hashmaps with data
    @PostConstruct
    public void load() throws Exception {
        gtfsDir = Path.of(gtfsDirStr);
        log.info("Loading GTFS static data from {}", gtfsDir.toAbsolutePath());
        loadRoutes();
        loadTrips();
        loadStopTimes();
        loadCalendar();
        loadCalendarDates();
        log.info("GTFS loaded: {} trips at stop {}", stopTimes.size(), stopId);
    }

    private void loadStopTimes() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(gtfsDir.resolve("stop_times.txt"))) {
            reader.readLine(); // skip line
            String line; 
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(","); 
                // If correct stop id, add to hashmap 
                if (cols[3].equals(stopId)) {
                    String tripId = cols[0]; 
                    String timeStr = cols[1];
                     if (Integer.parseInt(timeStr.split(":")[0]) < 24) {
                        stopTimes.put(tripId, LocalTime.parse(timeStr));
                    }
                }
            }
        }
    }

    private void loadTrips() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(gtfsDir.resolve("trips.txt"))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",");
                // route_id, service_id, trip_id
                trips.put(cols[2], new String[]{cols[1], cols[0]});
            }
        }
    }

    private void loadRoutes() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(gtfsDir.resolve("routes.txt"))) {
            reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",");
                routes.put(cols[0], cols[2]); // routeId -> short name
            }
        }
    }

    private void loadCalendar() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(gtfsDir.resolve("calendar.txt"))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",");
                calendar.put(cols[0], cols);
            }
        }
    }

    private void loadCalendarDates() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(gtfsDir.resolve("calendar_dates.txt"))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",");
                calendarDates
                    .computeIfAbsent(cols[0], k -> new HashMap<>())
                    .put(cols[1], Integer.parseInt(cols[2].trim()));
            }
        }
    }

    /* Get methods */ 
    public Map<String, LocalTime> getStopTimes() { return stopTimes; }
    public Map<String, String[]> getTrips() { return trips; }
    public Map<String, String> getRoutes() { return routes; }
    public Map<String, String[]> getCalendar() { return calendar; }
    public Map<String, Map<String, Integer>> getCalendarDates() { return calendarDates; }

    // Returns all buses after now 
    public List<UpcomingBus> getNextBuses() {
        List<UpcomingBus> nextBuses = new ArrayList<>(); 

        // Get today's day of week, date, and time
        LocalTime now = LocalTime.now(); 
        LocalDate date = LocalDate.now(); 
        //! Harcoded values
        // LocalTime now = LocalTime.of(13, 0); // hardcoded 1pm
        // LocalDate date = LocalDate.of(2026, 4, 7); // Tuesday
        //! 
        int weekDay = date.getDayOfWeek().getValue(); // 1 - 7
        String todayStr = date.format(DateTimeFormatter.BASIC_ISO_DATE); // "20260405"


        for (String tripId : stopTimes.keySet()) {
            LocalTime arrivalTime = stopTimes.get(tripId); 

            if (!trips.containsKey(tripId)) continue;
            // Continue if in trip in past 
            if (arrivalTime.isBefore(now.minusMinutes(30))) continue; // grace period in case of delay 
            

            // Get serviceId and routeId
            String[] pair = trips.get(tripId); 
            String serviceId = pair[0]; 
            String routeId = pair[1]; 

            if (!calendar.containsKey(serviceId)) continue;

            String[] days = calendar.get(serviceId);
            String startDate = days[8];
            String endDate = days[9].trim();
            // Check if correct week date and date range 
            if (days[weekDay].equals("1") && 
                todayStr.compareTo(startDate) >= 0 && 
                todayStr.compareTo(endDate) <= 0) {
                    // Check for exceptions 
                    Map<String, Integer> exceptions = calendarDates.get(serviceId);
                    if (exceptions != null && exceptions.containsKey(todayStr)) {
                        int type = exceptions.get(todayStr);
                        if (type == 2) continue; // service removed today, skip
                    }
                    // Create Bus object and add to output list
                    String routeName = routes.getOrDefault(routeId, "Unknown");
                    UpcomingBus bus = new UpcomingBus(tripId, routeName, arrivalTime);
                    nextBuses.add(bus);
            }
        }
        // Sort buses on arrivals
        nextBuses.sort(Comparator.comparing(UpcomingBus::getScheduledTime));
        return nextBuses; 
    }
}


