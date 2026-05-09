package ie.bustracker.app.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import ie.bustracker.app.models.UpcomingBus;
import ie.bustracker.app.services.RealTimeService;

@RestController
public class BusController {
	private final RealTimeService realTimeService;

	public BusController(RealTimeService realTimeService) {
        this.realTimeService = realTimeService;
    }

	// Get the JSON with all trips 
  	@GetMapping("/buses")
  	public List<UpcomingBus> getBuses() {
        return realTimeService.getCachedBuses(); 
 	}
}

