package ie.bustracker.app.services;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.*;

import ie.bustracker.app.config.NotificationProperties;
import ie.bustracker.app.models.UpcomingBus;

@Component
public class BusPollingScheduler {
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
        System.out.println("Scheduled poll firing at " + java.time.LocalTime.now());
        refresh(); 
    }

    // Call API and update cache
    private void refresh() {
        try {
            List<UpcomingBus> buses = realTimeService.getUpcomingBuses();
            realTimeService.setCachedBuses(buses);
            notificationService.notify(buses);
        } catch (Exception e) {
            System.out.println("Error: Bad poll"); 
        }
    }

}
