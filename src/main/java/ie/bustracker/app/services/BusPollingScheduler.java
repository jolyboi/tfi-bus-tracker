package ie.bustracker.app.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.List;

import ie.bustracker.app.models.UpcomingBus;

@Component
public class BusPollingScheduler {
    private static final Logger log = LoggerFactory.getLogger(BusPollingScheduler.class);

    private final RealTimeService realTimeService;
    private final NotificationService notificationService;

    public BusPollingScheduler(RealTimeService realTimeService, NotificationService notificationService) {
        this.realTimeService = realTimeService;
        this.notificationService = notificationService;
    }

    // Initial load in cache
    @PostConstruct
    public void load() {
        refresh();
    }

    // Poll every 30 seconds from 6 to 23 every day
    @Scheduled(cron = "0/30 * 6-23 * * *", zone = "Europe/Dublin")
    public void poll() {
        refresh();
    }

    // Call API and update cache
    private void refresh() {
        try {
            List<UpcomingBus> buses = realTimeService.getUpcomingBuses();
            realTimeService.setCachedBuses(buses);
            notificationService.notify(buses);
        } catch (Exception e) {
            log.warn("Bus poll failed; keeping previous cache", e);
        }
    }

}
