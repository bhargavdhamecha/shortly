package com.custom.url.shortner.config;

import com.netflix.appinfo.EurekaInstanceConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Resolves the Snowflake identity from deployment-owned, stable host configuration. */
@Configuration
public class EurekaConfig {
    private static final int MAX_ID = 31;

    private final EurekaInstanceConfig eurekaInstanceConfig;
    private final String datacenterIdValue;
    private final String machineIdsValue;

    public EurekaConfig(
            EurekaInstanceConfig eurekaInstanceConfig,
            @Value("${snowflake.datacenter-id:}") String datacenterIdValue,
            @Value("${snowflake.machine-ids:}") String machineIdsValue) {
        this.eurekaInstanceConfig = eurekaInstanceConfig;
        this.datacenterIdValue = datacenterIdValue;
        this.machineIdsValue = machineIdsValue;
    }

    public long getDatacenterId() {
        return parseId(datacenterIdValue, "Datacenter ID");
    }

    public long getMachineId() {
        String hostname = eurekaInstanceConfig.getHostName(false);
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalStateException("Stable Snowflake hostname is required");
        }

        Map<String, Integer> machineIds = parseMachineIds();
        Integer machineId = machineIds.get(hostname.trim());
        if (machineId == null) {
            throw new IllegalStateException("No Snowflake machine ID is configured for hostname '" + hostname + "'");
        }
        return machineId;
    }

    private Map<String, Integer> parseMachineIds() {
        if (machineIdsValue == null || machineIdsValue.isBlank()) {
            throw new IllegalStateException("Snowflake machine IDs must be configured as hostname=id entries");
        }

        Map<String, Integer> machineIds = new HashMap<>();
        Set<Integer> assignedIds = new HashSet<>();
        for (String entry : machineIdsValue.split(",")) {
            String[] parts = entry.trim().split("=", -1);
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("Invalid Snowflake machine ID entry '" + entry + "'; expected hostname=id");
            }

            String hostname = parts[0].trim();
            int machineId = Math.toIntExact(parseId(parts[1].trim(), "Machine ID for hostname '" + hostname + "'"));
            if (machineIds.putIfAbsent(hostname, machineId) != null) {
                throw new IllegalArgumentException("Duplicate Snowflake hostname mapping for '" + hostname + "'");
            }
            if (!assignedIds.add(machineId)) {
                throw new IllegalArgumentException("Snowflake machine ID " + machineId + " is assigned to more than one hostname");
            }
        }
        return machineIds;
    }

    private static int parseId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be explicitly configured in the range 0–" + MAX_ID);
        }
        final int id;
        try {
            id = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer in the range 0–" + MAX_ID, exception);
        }
        if (id < 0 || id > MAX_ID) {
            throw new IllegalArgumentException(name + " must be in the range 0–" + MAX_ID);
        }
        return id;
    }
}
