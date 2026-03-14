package io.jhpark.kopic.ws.engine.infra.rpc;

import io.jhpark.kopic.ws.engine.app.EngineAck;
import io.jhpark.kopic.ws.engine.app.EngineEnvelopeRequest;
import io.jhpark.kopic.ws.engine.app.GameEngineClient;
import io.jhpark.kopic.ws.engine.app.SessionLifecycleEvent;
import io.jhpark.kopic.ws.routing.app.RoomDirectory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@Slf4j
public class DummyGameEngineClient implements GameEngineClient {

	private final RoomDirectory roomDirectory;

	public DummyGameEngineClient(RoomDirectory roomDirectory) {
		this.roomDirectory = roomDirectory;
	}

	@Override
	public EngineAck send(EngineEnvelopeRequest request) {
		boolean accepted = roomDirectory.findOwner(request.roomId()).isPresent();
		log.info("engine dummy envelope roomId={} userId={} eventCode={} requestId={} accepted={}", request.roomId(), request.userId(), request.envelope().eventCode(), request.envelope().requestId(), accepted);
		return new EngineAck(accepted);
	}

	@Override
	public EngineAck send(SessionLifecycleEvent event) {
		boolean accepted = roomDirectory.findOwner(event.roomId()).isPresent();
		log.info("engine dummy lifecycle roomId={} userId={} type={} accepted={}", event.roomId(), event.userId(), event.type(), accepted);
		return new EngineAck(accepted);
	}
}
