package com.custom.url.shortner.service;

import com.custom.url.shortner.config.EurekaConfig;
import com.netflix.appinfo.EurekaInstanceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnowflakeIdGeneratorTests {
    private static final long SEQUENCE_BITS = 12;
    private static final long MACHINE_ID_BITS = 5;

    @Test
    void initializesIdentityOnceAndKeepsItsBitsStable() {
        EurekaInstanceConfig instanceConfig = mock(EurekaInstanceConfig.class);
        when(instanceConfig.getHostName(false)).thenReturn("node-a");
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(
                new EurekaConfig(instanceConfig, "9", "node-a=23"));

        generator.initialize();
        long firstId = generator.generateId();
        long secondId = generator.generateId();

        assertEquals(9, (firstId >>> (MACHINE_ID_BITS + SEQUENCE_BITS)) & 31);
        assertEquals(23, (firstId >>> SEQUENCE_BITS) & 31);
        assertEquals(9, (secondId >>> (MACHINE_ID_BITS + SEQUENCE_BITS)) & 31);
        assertEquals(23, (secondId >>> SEQUENCE_BITS) & 31);
    }
}
