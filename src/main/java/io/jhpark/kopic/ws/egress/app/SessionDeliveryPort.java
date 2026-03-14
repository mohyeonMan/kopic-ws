package io.jhpark.kopic.ws.egress.app;

import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;

public interface SessionDeliveryPort {

	void deliver(String userId, ServerEnvelope event);
}
