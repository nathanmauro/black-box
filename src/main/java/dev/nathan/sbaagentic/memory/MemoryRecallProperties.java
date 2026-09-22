package dev.nathan.sbaagentic.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sba.memory.recall")
public class MemoryRecallProperties {

    private double relevanceFloor = 0.61;

    public double getRelevanceFloor() {

        return relevanceFloor;
    }

    public void setRelevanceFloor(double relevanceFloor) {
        this.relevanceFloor = relevanceFloor;
    }
}
