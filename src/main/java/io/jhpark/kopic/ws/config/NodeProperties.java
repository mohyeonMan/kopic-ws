package io.jhpark.kopic.ws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic")
public record NodeProperties(
	String nodeId
) {

	public NodeProperties {
		nodeId = nodeId == null || nodeId.isBlank() ? "ws-local" : nodeId;
	}
}
