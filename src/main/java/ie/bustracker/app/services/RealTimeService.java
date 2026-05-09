package ie.bustracker.app.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;

import java.util.*;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import ie.bustracker.app.models.UpcomingBus;

@Service
public class RealTimeService {
    private final GtfsStaticService gtfs;

	public RealTimeService(GtfsStaticService gtfs) {
        this.gtfs = gtfs;
    }
    
    @Value("${bus-tracker.api-key}")
  	private String apiKey; 
    private String url = "https://api.nationaltransport.ie/gtfsr/v2/TripUpdates";   // api endpoint 5
  	private RestClient restClient = RestClient.builder().build();

    // Stop we're interested in     
    @Value("${bus-tracker.stop-id}")
    private String wantedStopId; 

    private volatile List<UpcomingBus> cachedBuses = Collections.emptyList();

    // Getter and Setter for cachedBuses 
    public List<UpcomingBus> getCachedBuses() {
        return cachedBuses;
    }
    
    public void setCachedBuses(List<UpcomingBus> newCachedBuses) {
        this.cachedBuses = newCachedBuses; 
    }

    public List<UpcomingBus> getUpcomingBuses() {
        List<UpcomingBus> nextBuses = gtfs.getNextBuses();

        try {
            // Receive stream of bytes
            byte[] bytes = restClient.get()
                                .uri(url)
                                .header("x-api-key", apiKey)
                                .retrieve()
                                .body(byte[].class);

            FeedMessage feed = FeedMessage.parseFrom(bytes);
            System.out.println("Realtime API accessed successfully."); 

            // tripId -> delay
            Map<String, Integer> delays = new HashMap<>();
            // tripId -> real time (with possible delay)
            Map<String, Long> realTimes = new HashMap<>();
            // Array for unscheduled buses
            List<UpcomingBus> addedBuses = new ArrayList<>(); 

            // Populate hashmaps with delays and arrival times provided by GTFS API
            for (FeedEntity entity : feed.getEntityList()) {
                if (entity.hasTripUpdate()) {

                    // Get tripId
                    String tripId = entity.getTripUpdate().getTrip().getTripId();
                    boolean isAdded = !entity.getTripUpdate().getTrip().hasTripId();
                   
                    // Loop through the list of StopTimeUpdate objects (they contain stop_id and arival)
                    for (var stopUpdate : entity.getTripUpdate().getStopTimeUpdateList()) {
                        String curStopId = stopUpdate.getStopId();

                        // Continue if curStopId is not wanted
                        if (!wantedStopId.equals(curStopId)) continue;

                        // Handle unscheduled buses 
                        if (isAdded) {
                            if (!stopUpdate.getArrival().hasTime()) continue; // skip if no absolute time

                            long timestamp = stopUpdate.getArrival().getTime();
                            LocalTime arrivalTime = Instant.ofEpochSecond(timestamp)
                                                        .atZone(ZoneId.of("Europe/Dublin"))
                                                        .toLocalTime();
                            
                            String routeId = entity.getTripUpdate().getTrip().getRouteId(); // e.g. "2 220 c b"
                            String routeName = gtfs.getRoutes().getOrDefault(routeId, routeId);

                            String entityId = entity.getId(); 
                            realTimes.put(entityId, timestamp); 
                            
                            UpcomingBus addedBus = new UpcomingBus(null, routeName, null);
                            addedBus.setActualTime(arrivalTime);
                            addedBuses.add(addedBus);
                        } 
                        // Scheduled buses 
                        else {
                            // If estimated time of arrival is given
                            if (stopUpdate.getArrival().hasTime()) {
                                var arrivalTime = stopUpdate.getArrival().getTime();
                                realTimes.put(tripId, arrivalTime);
                            }
                            // if stop found and has delay
                            else if (stopUpdate.getArrival().hasDelay()) {
                                int delay = stopUpdate.getArrival().getDelay();
                                delays.put(tripId, delay);
                            }
                        }
                      
                    }
                }
            }

            System.out.println("Delays found: " + delays.size() + " " + delays.keySet());
            System.out.println("Real times found: " + realTimes.size() + " " + realTimes.keySet());


            // Add actual time to each bus if applicable
            for (UpcomingBus bus : nextBuses) {

                System.out.println("Scheduled bus id - " + bus.getTripId()); 

                String busTripId = bus.getTripId();
                LocalTime busActualTime = null;
                if (delays.containsKey(busTripId)) {
                    int delay = delays.get(busTripId);
                    busActualTime = bus.getScheduledTime().plusSeconds(delay);
                }
                else if (realTimes.containsKey(busTripId)) {
                    Long timestamp = realTimes.get(busTripId);
                    busActualTime = Instant.ofEpochSecond(timestamp).atZone(ZoneId.of("Europe/Dublin")).toLocalTime();
                }
                bus.setActualTime(busActualTime);
            }

            // Add unscheduled buses to main buses array 
            nextBuses.addAll(addedBuses); 
            nextBuses.sort(Comparator.comparing(b -> b.getActualTime() != null ? b.getActualTime() : b.getScheduledTime()));


        } catch (Exception e) {
            System.out.println("Realtime API failed, returning static schedule: " + e.getMessage());
        }
        

        return nextBuses;
    }
}
