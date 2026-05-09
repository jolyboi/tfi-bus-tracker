package ie.bustracker.app.models;

import java.time.LocalTime; 

public class UpcomingBus {

    private String tripId; 
    private String routeName;  // human readable 
    private LocalTime scheduledTime;
    private LocalTime actualTime;

    // Constructor
    public UpcomingBus(String tripId, String routeName, LocalTime scheduledTime) {
        this.tripId = tripId;
        this.routeName = routeName;
        this.scheduledTime = scheduledTime; 
        this.actualTime = null; 
    }

    // Constructor for unscheduled buses 
    public UpcomingBus(String tripId, String routeName) {
        this(tripId, routeName, null);
    }

    public String toString() {
        return routeName + " at " + scheduledTime + " (" + tripId + ")";
    }

    // Setter for actualTime
    public void setActualTime(LocalTime newActualTime) {
        this.actualTime = newActualTime; 
    }

    // Getters 
    public String getTripId() {
        return this.tripId;
    }

    public String getRouteName() {
        return this.routeName;
    }

    public LocalTime getScheduledTime() {
        return this.scheduledTime;
    }

     public LocalTime getActualTime() {
        return this.actualTime;
    }
}