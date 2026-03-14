package io.jhpark.kopic.ws.ingress.app;

import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;

public interface EnvelopeValidator {

	ClientEnvelope validate(String payload);
}
