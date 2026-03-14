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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
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
		log.info("dispatch connected userId={} roomId={}", session.getUserId(), session.getRoomId());
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
		log.info("dispatch message userId={} roomId={} eventCode={} requestId={}", session.getUserId(), session.getRoomId(), envelope.eventCode(), envelope.requestId());
		if (envelope.eventCode() == EVENT_PING) {
			log.info("dispatch ping->pong userId={} roomId={} requestId={}", session.getUserId(), session.getRoomId(), envelope.requestId());
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
		log.info("dispatch disconnected userId={} roomId={}", session.getUserId(), session.getRoomId());
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
