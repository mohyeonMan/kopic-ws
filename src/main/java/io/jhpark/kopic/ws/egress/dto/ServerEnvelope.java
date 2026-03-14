package io.jhpark.kopic.ws.egress.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ServerEnvelope(
	int eventCode,
	JsonNode payload,
	String requestId
) {
}
