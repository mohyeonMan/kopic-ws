package io.jhpark.kopic.ws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.ws")
public record WebSocketProperties(
	String endpoint,
	int maxTextMessageSize
) {

	public WebSocketProperties {
		endpoint = endpoint == null || endpoint.isBlank() ? "/ws" : endpoint;
		maxTextMessageSize = maxTextMessageSize <= 0 ? 8192 : maxTextMessageSize;
	}
}
