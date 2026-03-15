package io.jhpark.kopic.ws.dispatch.app;

import io.jhpark.kopic.ws.engine.app.EngineAckReason;

public class EngineRequestRejectedException extends RuntimeException {

	private final EngineAckReason reason;

	public EngineRequestRejectedException(String message, EngineAckReason reason) {
		super(message);
		this.reason = reason;
	}

	public EngineAckReason getReason() {
		return reason;
	}
}
