package io.jhpark.kopic.ws.engine.app;

import java.time.Instant;

public record SessionLifecycleEvent(
	String roomId,
	String userId,
	Instant occurredAt,
	SessionLifecycleType type
) {
}
