package io.jhpark.kopic.ws.ingress.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ClientEnvelope(
	int e,
	JsonNode p,
	String rid
) {
}
