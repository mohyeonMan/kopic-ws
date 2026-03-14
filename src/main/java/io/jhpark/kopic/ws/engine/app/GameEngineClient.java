package io.jhpark.kopic.ws.engine.app;

public interface GameEngineClient {

	EngineAck send(EngineEnvelopeRequest request);

	EngineAck send(SessionLifecycleEvent event);
}
