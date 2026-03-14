package io.jhpark.kopic.ws.routing.domain;

public record EngineRoute(
	String engineId,
	String host,
	int port
) {
}
