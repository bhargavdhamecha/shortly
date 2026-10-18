package com.custom.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.profiles.active=local",
		"GATEWAY_REDIS_URI=redis://localhost:6380",
		"app.rate-limit.redis-uri=redis://localhost:6380"
})
class GatewayApplicationTests {

	@Test
	void contextLoads() {
	}

}
