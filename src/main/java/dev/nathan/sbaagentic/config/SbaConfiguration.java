package dev.nathan.sbaagentic.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SbaProperties.class)
public class SbaConfiguration {
}
