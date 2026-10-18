package com.custom.url.shortner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@ConfigurationPropertiesScan
@SpringBootApplication
public class ShortnerApplication {

	@Bean
	static BeanFactoryPostProcessor requireDeploymentSecrets(Environment environment) {
		return beanFactory -> {
			requireNonBlank(environment, "DB_USERNAME");
			requireNonBlank(environment, "DB_PASSWORD");
			String redisPassword = environment.getProperty("REDIS_PASSWORD");
			if (redisPassword == null) {
				throw new IllegalStateException("Required configuration REDIS_PASSWORD is missing");
			}
			if (redisPassword.isBlank() && !isLocal(environment)) {
				throw new IllegalStateException("REDIS_PASSWORD must be nonblank outside the local profile");
			}
			if (isProduction(environment)
					&& ("s_user".equals(environment.getProperty("DB_USERNAME"))
							|| "s_password".equals(environment.getProperty("DB_PASSWORD")))) {
				throw new IllegalStateException("Production must not use local Compose database credentials");
			}
		};
	}

	private static boolean isLocal(Environment environment) {
		return java.util.Arrays.asList(environment.getActiveProfiles()).contains("local");
	}

	private static boolean isProduction(Environment environment) {
		return java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
	}

	private static void requireNonBlank(Environment environment, String name) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Required configuration " + name + " is missing or blank");
		}
	}

	public static void main(String[] args) {
		SpringApplication.run(ShortnerApplication.class, args);
	}

}
