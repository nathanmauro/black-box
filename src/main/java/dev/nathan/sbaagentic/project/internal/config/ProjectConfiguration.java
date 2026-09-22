package dev.nathan.sbaagentic.project.internal.config;

import dev.nathan.sbaagentic.project.EditorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EditorProperties.class)
public class ProjectConfiguration {}
