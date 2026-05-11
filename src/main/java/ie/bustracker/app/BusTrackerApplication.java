package ie.bustracker.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import ie.bustracker.app.config.NotificationProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties.class)
public class BusTrackerApplication {

	public static void main(String[] args) {
		SpringApplication.run(BusTrackerApplication.class, args);
	}

}
