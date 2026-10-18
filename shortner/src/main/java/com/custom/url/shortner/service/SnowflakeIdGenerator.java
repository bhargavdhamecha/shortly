package com.custom.url.shortner.service;


import com.custom.url.shortner.config.EurekaConfig;
import com.custom.url.shortner.exception.IdGenerationException;
import com.custom.url.shortner.exception.SystemClockDriftException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnowflakeIdGenerator {
    private static final long EPOCH = 1791871200000L; // Company's epoch = Tuesday, October 13, 2026 12:00:00 AM
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long MACHINE_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long SEQUENCE_MASK = -1L ^ (-1L << SEQUENCE_BITS);
    private long datacenterId;
    private long machineId;

    // Using primitive variables since 'synchronized' handles thread memory visibility
    private long sequence = 0L;
    private long lastTimestamp = -1L;
    private final EurekaConfig eurekaConfig;

    public SnowflakeIdGenerator(EurekaConfig eurekaConfig) {
        this.eurekaConfig = eurekaConfig;
    }

    @PostConstruct
    public void initialize() {
        this.datacenterId = eurekaConfig.getDatacenterId();
        this.machineId = eurekaConfig.getMachineId();
        log.info("Snowflake identity configured -> DC: {}, Machine: {}", datacenterId, machineId);
    }

    public synchronized long generateId() {
        long timestamp = System.currentTimeMillis();

        // 1. Guard against Clock Drift
        if (timestamp < lastTimestamp) {
            throw new SystemClockDriftException("Clock moved backwards. Refusing to generate id for " + (lastTimestamp - timestamp) + "ms");
        }

        // 2. Handle ID requests in the exact same millisecond
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            // Sequence overflow: block execution until the clock moves forward 1 millisecond
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            // New millisecond reached: reset sequence counter to 0
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 3. Compute final 64-bit Long ID
        return ((timestamp - EPOCH) << (DATACENTER_ID_BITS + MACHINE_ID_BITS + SEQUENCE_BITS))
                | (datacenterId << (MACHINE_ID_BITS + SEQUENCE_BITS))
                | (machineId << SEQUENCE_BITS)
                | sequence;
    }

    private long waitForNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            // A simple 1ms sleep prevents the CPU from running a hot 100% busy-loop while waiting
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdGenerationException("Thread interrupted while waiting for next millisecond", e);
            }
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

}
