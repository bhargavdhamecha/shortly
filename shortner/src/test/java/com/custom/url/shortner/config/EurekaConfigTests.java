package com.custom.url.shortner.config;

import com.netflix.appinfo.EurekaInstanceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EurekaConfigTests {
    private EurekaInstanceConfig instanceConfig;

    @BeforeEach
    void setUp() {
        instanceConfig = mock(EurekaInstanceConfig.class);
        when(instanceConfig.getHostName(false)).thenReturn("node-a");
    }

    @Test
    void resolvesExplicitDatacenterAndHostnameMachineIds() {
        EurekaConfig config = new EurekaConfig(instanceConfig, "7", "node-a=12,node-b=13");

        assertEquals(7, config.getDatacenterId());
        assertEquals(12, config.getMachineId());
    }

    @Test
    void rejectsMissingDatacenterId() {
        EurekaConfig config = new EurekaConfig(instanceConfig, "", "node-a=12");

        assertThrows(IllegalStateException.class, config::getDatacenterId);
    }

    @Test
    void rejectsOutOfRangeIds() {
        EurekaConfig invalidDatacenter = new EurekaConfig(instanceConfig, "32", "node-a=12");
        EurekaConfig invalidMachine = new EurekaConfig(instanceConfig, "7", "node-a=-1");

        assertThrows(IllegalArgumentException.class, invalidDatacenter::getDatacenterId);
        assertThrows(IllegalArgumentException.class, invalidMachine::getMachineId);
    }

    @Test
    void rejectsMissingHostnameMapping() {
        EurekaConfig config = new EurekaConfig(instanceConfig, "7", "node-b=12");

        assertThrows(IllegalStateException.class, config::getMachineId);
    }

    @Test
    void rejectsMachineSlotAssignedToMultipleHostnames() {
        EurekaConfig config = new EurekaConfig(instanceConfig, "7", "node-a=12,node-b=12");

        assertThrows(IllegalArgumentException.class, config::getMachineId);
    }
}
