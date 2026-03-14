package io.jhpark.kopic.ws.ingress.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ClientEnvelope(
	int eventCode,
	JsonNode payload,
	String requestId
) {
}
