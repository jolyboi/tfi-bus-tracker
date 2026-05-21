package ie.bustracker.app.services;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import ie.bustracker.app.config.NotificationProperties;
import ie.bustracker.app.models.UpcomingBus;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationProperties props;
    private final RestClient restClient = RestClient.builder().build(); 
    private final Set<String> notifiedTripIds = ConcurrentHashMap.newKeySet(); // Thread safe set 

    // Constructor 
    public NotificationService(NotificationProperties props) {
        this.props = props; 
    }

    public void notify(List<UpcomingBus> buses) {
        // If notificaitons or url not set
        if (!props.enabled() || props.ntfyUrl() == null || props.ntfyUrl().isBlank()) return; 

        LocalTime now = LocalTime.now(ZoneId.of("Europe/Dublin")).truncatedTo(ChronoUnit.MINUTES);

        for (UpcomingBus bus : buses) {
            if (bus.getTripId() == null) continue;

            // Get arrival time
            LocalTime arrival = (bus.getActualTime() != null) ? bus.getActualTime() : bus.getScheduledTime();
            // Minutes until arrival 
            long minutes = Duration.between(now, arrival).toMinutes();

            // Skip block
            if (minutes < 0) continue;                        // bus already passed
            if (minutes > props.minutesThreshold()) continue; // bus too far away 
            if (!props.routes().isEmpty() && !props.routes().contains(bus.getRouteName())) continue; // unwanted bus route
            if (notifiedTripIds.contains(bus.getTripId())) continue; // already notified about bus 

            // If notification successful 
            if (sendNotification(bus, minutes)) {
                notifiedTripIds.add(bus.getTripId()); 
            }
        }

        // Drop tripIds no longer in the feed so the set doesn't grow unbounded
        // and so recycled tripIds (same id on a later day) get notified again.
        Set<String> currentIds = new HashSet<>();
        for (UpcomingBus bus : buses) {
            if (bus.getTripId() != null) currentIds.add(bus.getTripId());
        }
        notifiedTripIds.retainAll(currentIds);

    }

    private boolean sendNotification(UpcomingBus bus, long minutes) {
        String body = bus.getRouteName() + " in " + minutes + " min\n"
                + "Expected Arrival: " 
                + (bus.getActualTime() != null ? bus.getActualTime() : bus.getScheduledTime());

        try { 
            restClient.post()
                    .uri(props.ntfyUrl())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("ntfy send failed for route {}", bus.getRouteName(), e);
            return false;
        }
    }
}
