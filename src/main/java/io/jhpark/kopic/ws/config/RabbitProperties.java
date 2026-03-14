package io.jhpark.kopic.ws.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.rabbit")
public record RabbitProperties(
	String outboundExchange,
	String inboundQueuePrefix,
	String inboundRoutingKeyPrefix
) {

	public RabbitProperties {
		outboundExchange = normalize(outboundExchange, "kopic.engine.outbound");
		inboundQueuePrefix = normalize(inboundQueuePrefix, "kopic.ws.node.");
		inboundRoutingKeyPrefix = normalize(inboundRoutingKeyPrefix, "ws.node.");
	}

	private static String normalize(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}

	public String queueName(String nodeId) {
		return inboundQueuePrefix + nodeId;
	}

	public String routingKey(String nodeId) {
		return inboundRoutingKeyPrefix + nodeId;
	}
}
