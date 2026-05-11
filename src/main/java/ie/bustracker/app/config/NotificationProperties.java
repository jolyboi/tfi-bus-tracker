package ie.bustracker.app.config;

import java.util.List; 

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bus-tracker.notifications")
public record NotificationProperties(
        boolean enabled,
        int minutesThreshold,
        List<String> routes,
        String ntfyUrl
) {}