package io.jhpark.kopic.ws.subscription.app;

public interface EngineEventSubscriber {

	void handle(InboundEngineEvent event);
}
