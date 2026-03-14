package io.jhpark.kopic.ws.subscription.app;

import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;

public record InboundEngineEvent(
	String userId,
	ServerEnvelope envelope
) {
}
