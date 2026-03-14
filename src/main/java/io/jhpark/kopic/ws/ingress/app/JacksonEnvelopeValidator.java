package io.jhpark.kopic.ws.ingress.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;
import org.springframework.stereotype.Component;

@Component
public class JacksonEnvelopeValidator implements EnvelopeValidator {

	private final ObjectMapper objectMapper;

	public JacksonEnvelopeValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public ClientEnvelope validate(String payload) {
		try {
			JsonNode root = objectMapper.readTree(payload);
			JsonNode eventCode = root.get("e");
			JsonNode messagePayload = root.get("p");
			JsonNode requestId = root.get("rid");

			if (eventCode == null || !eventCode.isInt()) {
				throw new InvalidEnvelopeException("missing or invalid e");
			}

			if (messagePayload == null || !messagePayload.isObject()) {
				throw new InvalidEnvelopeException("missing or invalid p");
			}

			return new ClientEnvelope(
				eventCode.asInt(),
				messagePayload,
				requestId != null && requestId.isTextual() ? requestId.asText() : null
			);
		} catch (InvalidEnvelopeException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new InvalidEnvelopeException("invalid json");
		}
	}
}
