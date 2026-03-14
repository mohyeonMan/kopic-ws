package io.jhpark.kopic.ws.engine.infra.rpc;

import io.jhpark.kopic.ws.engine.app.EngineAck;
import io.jhpark.kopic.ws.engine.app.EngineEnvelopeRequest;
import io.jhpark.kopic.ws.engine.app.GameEngineClient;
import io.jhpark.kopic.ws.engine.app.SessionLifecycleEvent;
import io.jhpark.kopic.ws.routing.app.RoomDirectory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class DummyGameEngineClient implements GameEngineClient {

	private final RoomDirectory roomDirectory;

	public DummyGameEngineClient(RoomDirectory roomDirectory) {
		this.roomDirectory = roomDirectory;
	}

	@Override
	public EngineAck send(EngineEnvelopeRequest request) {
		return new EngineAck(roomDirectory.findOwner(request.roomId()).isPresent());
	}

	@Override
	public EngineAck send(SessionLifecycleEvent event) {
		return new EngineAck(roomDirectory.findOwner(event.roomId()).isPresent());
	}
}
