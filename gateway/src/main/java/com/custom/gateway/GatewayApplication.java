package com.custom.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Arrays;

@SpringBootApplication
public class GatewayApplication {

	@Bean
	static BeanFactoryPostProcessor requireGatewayRedisUri(Environment environment) {
		return beanFactory -> {
			String uri = environment.getProperty("GATEWAY_REDIS_URI");
			if (uri == null || uri.isBlank()) {
				throw new IllegalStateException("Required configuration GATEWAY_REDIS_URI is missing or blank");
			}
			if (!Arrays.asList(environment.getActiveProfiles()).contains("local")) {
				String userInfo;
				try {
					userInfo = URI.create(uri).getRawUserInfo();
				}
				catch (IllegalArgumentException exception) {
					throw new IllegalStateException("GATEWAY_REDIS_URI is not a valid URI");
				}
				int passwordSeparator = userInfo == null ? -1 : userInfo.indexOf(':');
				if (passwordSeparator < 0 || passwordSeparator == userInfo.length() - 1) {
					throw new IllegalStateException("GATEWAY_REDIS_URI must include Redis credentials outside the local profile");
				}
			}
		};
	}

	public static void main(String[] args) {
		SpringApplication.run(GatewayApplication.class, args);
	}

}
