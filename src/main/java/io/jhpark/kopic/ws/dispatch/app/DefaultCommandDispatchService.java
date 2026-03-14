package io.jhpark.kopic.ws.dispatch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.egress.app.SessionDeliveryPort;
import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;
import io.jhpark.kopic.ws.engine.app.EngineEnvelopeRequest;
import io.jhpark.kopic.ws.engine.app.GameEngineClient;
import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;
import io.jhpark.kopic.ws.engine.app.SessionLifecycleEvent;
import io.jhpark.kopic.ws.engine.app.SessionLifecycleType;
import io.jhpark.kopic.ws.session.domain.WsSession;
import org.springframework.stereotype.Service;

@Service
public class DefaultCommandDispatchService implements CommandDispatchService {

	private static final int EVENT_PING = 1;
	private static final int EVENT_PONG = 2;

	private final GameEngineClient gameEngineClient;
	private final SessionDeliveryPort sessionDeliveryPort;
	private final ObjectMapper objectMapper;

	public DefaultCommandDispatchService(
		GameEngineClient gameEngineClient,
		SessionDeliveryPort sessionDeliveryPort,
		ObjectMapper objectMapper
	) {
		this.gameEngineClient = gameEngineClient;
		this.sessionDeliveryPort = sessionDeliveryPort;
		this.objectMapper = objectMapper;
	}

	@Override
	public void handleConnected(WsSession session) {
		if (!gameEngineClient.send(new SessionLifecycleEvent(
			session.getRoomId(),
			session.getUserId(),
			session.getConnectedAt(),
			SessionLifecycleType.CONNECTED
		)).accepted()) {
			throw new EngineRequestRejectedException("engine rejected connect");
		}
	}

	@Override
	public void handleMessage(WsSession session, ClientEnvelope envelope) {
		if (envelope.eventCode() == EVENT_PING) {
			sessionDeliveryPort.deliver(
				session.getUserId(),
				new ServerEnvelope(EVENT_PONG, objectMapper.createObjectNode(), envelope.requestId())
			);
			return;
		}

		if (!gameEngineClient.send(new EngineEnvelopeRequest(
			session.getRoomId(),
			session.getUserId(),
			session.getLastSeenAt(),
			envelope
		)).accepted()) {
			throw new EngineRequestRejectedException("engine rejected message");
		}
	}

	@Override
	public void handleDisconnected(WsSession session) {
		if (!gameEngineClient.send(new SessionLifecycleEvent(
			session.getRoomId(),
			session.getUserId(),
			session.getLastSeenAt(),
			SessionLifecycleType.DISCONNECTED
		)).accepted()) {
			throw new EngineRequestRejectedException("engine rejected disconnect");
		}
	}
}
