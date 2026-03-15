package io.jhpark.kopic.ws.engine.app;

public enum EngineAckReason {
	ACCEPTED,
	NOT_OWNER,
	MIGRATING,
	REJECTED,
	INTERNAL_ERROR
}
