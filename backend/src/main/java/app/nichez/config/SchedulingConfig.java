package app.nichez.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs (the daily media purge). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
