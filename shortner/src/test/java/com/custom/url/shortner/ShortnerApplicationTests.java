package com.custom.url.shortner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.profiles.active=local",
		"snowflake.datacenter-id=0",
		"snowflake.machine-ids=localhost=0",
		"DB_USERNAME=s_user",
		"DB_PASSWORD=s_password",
		"REDIS_PASSWORD=",
		"spring.datasource.username=s_user",
		"spring.datasource.password=s_password",
		"spring.data.redis.password="
})
class ShortnerApplicationTests {

	@Test
	void contextLoads() {
	}

}
