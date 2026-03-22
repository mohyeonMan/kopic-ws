package io.jhpark.kopic.ws.ingress.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;
import org.junit.jupiter.api.Test;

class JacksonEnvelopeValidatorTest {

	private final JacksonEnvelopeValidator validator = new JacksonEnvelopeValidator(new ObjectMapper());

	@Test
	void validatesRegularRuntimeEvent() {
		ClientEnvelope envelope = validator.validate("""
			{"e":105,"p":{},"rid":"r-1"}
			""");

		assertEquals(105, envelope.e());
		assertEquals("r-1", envelope.rid());
	}

	@Test
	void allowsMissingRid() {
		ClientEnvelope envelope = validator.validate("""
			{"e":201,"p":{}}
			""");

		assertEquals(201, envelope.e());
		assertNull(envelope.rid());
	}

	@Test
	void rejectsInvalidPayloadShape() {
		assertThrows(InvalidEnvelopeException.class, () -> validator.validate("""
			{"e":201,"p":[]}
			"""));
	}
}
