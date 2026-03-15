package io.jhpark.kopic.ws.engine.app;

public record EngineAck(
	boolean accepted,
	EngineAckReason reason
) {

	public static EngineAck acceptedAck() {
		return new EngineAck(true, EngineAckReason.ACCEPTED);
	}

	public static EngineAck rejectedAck(EngineAckReason reason) {
		return new EngineAck(false, reason);
	}
}
