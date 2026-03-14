package io.jhpark.kopic.ws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.redis")
public record RedisProperties(
	String roomOwnerKeyPrefix,
	String engineRouteKeyPrefix
) {

	public RedisProperties {
		roomOwnerKeyPrefix = normalize(roomOwnerKeyPrefix, "room:owner:");
		engineRouteKeyPrefix = normalize(engineRouteKeyPrefix, "engine:route:");
	}

	private static String normalize(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}
}
