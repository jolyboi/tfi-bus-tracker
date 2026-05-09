package ie.bustracker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BusTrackerApplication {

	public static void main(String[] args) {
		SpringApplication.run(BusTrackerApplication.class, args);
	}

}
