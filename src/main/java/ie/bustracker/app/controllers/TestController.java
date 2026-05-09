package ie.bustracker.app.controllers;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Instant; 

import ie.bustracker.app.services.GtfsStaticService;



@RestController
public class TestController {
	private final GtfsStaticService gtfs;

	public TestController(GtfsStaticService gtfs) {
        this.gtfs = gtfs;
    }

	@Value("${bus-tracker.api-key}")
  	private String apiKey; 
  	private String url = "https://api.nationaltransport.ie/gtfsr/v2/TripUpdates";
  	private RestClient restClient = RestClient.builder().build();
	// Edenhall Stop 
	private String wanted_stop_id = "8380B246441";		// "8380B246441"; - my stop 

	// Get the JSON with all trips 
  	@GetMapping("/test")
  	public String test() throws Exception{
    	byte[] bytes = restClient.get()
                            .uri(url)
                            .header("x-api-key", apiKey)
                            .retrieve()
                            .body(byte[].class);
    
		FeedMessage feed = FeedMessage.parseFrom(bytes);
		// Find specific stop
		outerloop:  
		for (FeedEntity entity : feed.getEntityList()) {
			if (entity.hasTripUpdate()) {
				for (var stop_time : entity.getTripUpdate().getStopTimeUpdateList()) {
					var cur_stop_id = stop_time.getStopId();
					// If stop found and has time
					if (cur_stop_id.equals(wanted_stop_id) && stop_time.getArrival().hasTime()) {
						var arrival_time = stop_time.getArrival().getTime();
        				long time_now = Instant.now().getEpochSecond(); 
						long minutes_until_arrival = (arrival_time - time_now) / 60; 
						System.out.println("Minutes Left: " + minutes_until_arrival);
						break outerloop;
					}
					// if stop found and has time
					else if (cur_stop_id.equals(wanted_stop_id) && stop_time.getArrival().hasDelay()) {
						System.out.println(stop_time.getArrival().getDelay());
					}
				}
      		}
		}
		
		System.out.println(gtfs.getNextBuses());
    	String response = feed.toString();
    	return response; 
 	}
}
