package io.jhpark.kopic.ws.engine.app;

import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;
import java.time.Instant;

public record EngineEnvelopeRequest(
	String roomId,
	String userId,
	Instant occurredAt,
	ClientEnvelope envelope
) {
}
