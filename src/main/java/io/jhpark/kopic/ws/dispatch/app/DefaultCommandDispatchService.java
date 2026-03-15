package io.jhpark.kopic.ws.dispatch.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.egress.app.SessionDeliveryPort;
import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;
import io.jhpark.kopic.ws.engine.app.EngineAck;
import io.jhpark.kopic.ws.engine.app.EngineAckReason;
import io.jhpark.kopic.ws.engine.app.EngineEnvelopeRequest;
import io.jhpark.kopic.ws.engine.app.GameEngineClient;
import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;
import io.jhpark.kopic.ws.engine.app.SessionLifecycleEvent;
import io.jhpark.kopic.ws.engine.app.SessionLifecycleType;
import io.jhpark.kopic.ws.routing.app.RoomDirectory;
import io.jhpark.kopic.ws.session.domain.WsSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DefaultCommandDispatchService implements CommandDispatchService {

	private static final int EVENT_PING = 1;
	private static final int EVENT_PONG = 2;
	private static final int MAX_NOT_OWNER_ATTEMPTS = 2;
	private static final long RETRY_BACKOFF_MILLIS = 150L;

	private final GameEngineClient gameEngineClient;
	private final RoomDirectory roomDirectory;
	private final SessionDeliveryPort sessionDeliveryPort;
	private final ObjectMapper objectMapper;

	public DefaultCommandDispatchService(
		GameEngineClient gameEngineClient,
		RoomDirectory roomDirectory,
		SessionDeliveryPort sessionDeliveryPort,
		ObjectMapper objectMapper
	) {
		this.gameEngineClient = gameEngineClient;
		this.roomDirectory = roomDirectory;
		this.sessionDeliveryPort = sessionDeliveryPort;
		this.objectMapper = objectMapper;
	}

	@Override
	public void handleConnected(WsSession session) {
		log.info("dispatch connected userId={} roomId={}", session.getUserId(), session.getRoomId());
		EngineAck ack = sendWithOwnerRetry(
			session.getRoomId(),
			() -> gameEngineClient.send(new SessionLifecycleEvent(
			session.getRoomId(),
			session.getUserId(),
			session.getConnectedAt(),
			SessionLifecycleType.CONNECTED
		))
		);
		rejectIfNeeded(ack, "engine rejected connect");
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

		EngineAck ack = sendWithOwnerRetry(
			session.getRoomId(),
			() -> gameEngineClient.send(new EngineEnvelopeRequest(
			session.getRoomId(),
			session.getUserId(),
			session.getLastSeenAt(),
			envelope
		))
		);
		rejectIfNeeded(ack, "engine rejected message");
	}

	@Override
	public void handleDisconnected(WsSession session) {
		log.info("dispatch disconnected userId={} roomId={}", session.getUserId(), session.getRoomId());
		EngineAck ack = sendWithOwnerRetry(
			session.getRoomId(),
			() -> gameEngineClient.send(new SessionLifecycleEvent(
			session.getRoomId(),
			session.getUserId(),
			session.getLastSeenAt(),
			SessionLifecycleType.DISCONNECTED
		))
		);
		if (!ack.accepted()) {
			throw new EngineRequestRejectedException("engine rejected disconnect", ack.reason());
		}
	}

	private EngineAck sendWithOwnerRetry(String roomId, AckSupplier supplier) {
		EngineAck ack = supplier.send();
		if (ack.accepted()) {
			return ack;
		}

		if (ack.reason() == EngineAckReason.MIGRATING) {
			return ack;
		}

		for (int attempt = 2; attempt <= MAX_NOT_OWNER_ATTEMPTS; attempt++) {
			if (ack.reason() != EngineAckReason.NOT_OWNER) {
				return ack;
			}

			log.warn(
				"dispatch not-owner roomId={} invalidating owner cache and retrying attempt={}/{}",
				roomId,
				attempt,
				MAX_NOT_OWNER_ATTEMPTS
			);
			roomDirectory.invalidateOwnerCache(roomId);
			sleepBackoff();
			ack = supplier.send();
			if (ack.accepted()) {
				return ack;
			}
		}

		return ack;
	}

	private void rejectIfNeeded(EngineAck ack, String message) {
		if (!ack.accepted()) {
			throw new EngineRequestRejectedException(message, ack.reason());
		}
	}

	private void sleepBackoff() {
		try {
			Thread.sleep(RETRY_BACKOFF_MILLIS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	private interface AckSupplier {
		EngineAck send();
	}
}
