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
			JsonNode e = root.get("e");
			JsonNode p = root.get("p");
			JsonNode rid = root.get("rid");

			if (e == null || !e.isInt()) {
				throw new InvalidEnvelopeException("missing or invalid e");
			}

			if (p == null || !p.isObject()) {
				throw new InvalidEnvelopeException("missing or invalid p");
			}

			return new ClientEnvelope(
				e.asInt(),
				p,
				rid != null && rid.isTextual() ? rid.asText() : null
			);
		} catch (InvalidEnvelopeException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new InvalidEnvelopeException("invalid json");
		}
	}
}
