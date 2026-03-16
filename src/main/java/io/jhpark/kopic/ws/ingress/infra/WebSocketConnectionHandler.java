package io.jhpark.kopic.ws.ingress.infra;

import io.jhpark.kopic.ws.config.WebSocketProperties;
import io.jhpark.kopic.ws.dispatch.app.CommandDispatchService;
import io.jhpark.kopic.ws.dispatch.app.EngineRequestRejectedException;
import io.jhpark.kopic.ws.engine.app.EngineAckReason;
import io.jhpark.kopic.ws.ingress.app.EnvelopeValidator;
import io.jhpark.kopic.ws.ingress.app.InvalidEnvelopeException;
import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;
import io.jhpark.kopic.ws.session.app.SessionRegistry;
import io.jhpark.kopic.ws.session.domain.WsSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@Slf4j
public class WebSocketConnectionHandler extends TextWebSocketHandler {

	private final SessionRegistry sessionRegistry;
	private final CommandDispatchService commandDispatchService;
	private final EnvelopeValidator envelopeValidator;
	private final WebSocketProperties webSocketProperties;
	private final ObjectMapper objectMapper;

	public WebSocketConnectionHandler(
		SessionRegistry sessionRegistry,
		CommandDispatchService commandDispatchService,
		EnvelopeValidator envelopeValidator,
		WebSocketProperties webSocketProperties,
		ObjectMapper objectMapper
	) {
		this.sessionRegistry = sessionRegistry;
		this.commandDispatchService = commandDispatchService;
		this.envelopeValidator = envelopeValidator;
		this.webSocketProperties = webSocketProperties;
		this.objectMapper = objectMapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		session.setTextMessageSizeLimit(webSocketProperties.maxTextMessageSize());

		WsSession wsSession = new WsSession(
			session,
			requireTextAttribute(session, RoomHandshakeInterceptor.ATTR_USER_ID),
			requireTextAttribute(session, RoomHandshakeInterceptor.ATTR_ROOM_ID),
			Instant.now(),
			Instant.now()
		);
		sessionRegistry.save(wsSession);
		log.info("ws connected sessionId={} userId={} roomId={}", session.getId(), wsSession.getUserId(), wsSession.getRoomId());
		try {
			commandDispatchService.handleConnected(wsSession);
			log.info("ws internal join requested sessionId={} userId={} roomId={}", session.getId(), wsSession.getUserId(), wsSession.getRoomId());
		} catch (EngineRequestRejectedException exception) {
			log.warn("ws internal join rejected sessionId={} userId={} roomId={} reason={}", session.getId(), wsSession.getUserId(), wsSession.getRoomId(), exception.getReason());
			sessionRegistry.remove(session.getId());
			closeQuietly(session, CloseStatus.SERVER_ERROR);
		}
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		sessionRegistry.touch(session.getId(), Instant.now())
			.ifPresent(touchedSession -> processTextMessage(session, touchedSession, message));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		log.info("ws disconnected sessionId={} code={} reason={}", session.getId(), status.getCode(), status.getReason());
		sessionRegistry.findBySessionId(session.getId()).ifPresent(commandDispatchService::handleDisconnected);
		sessionRegistry.remove(session.getId());
	}

	private String requireTextAttribute(WebSocketSession session, String attributeName) {
		Object value = session.getAttributes().get(attributeName);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw new IllegalStateException("missing required handshake attribute: " + attributeName);
	}

	private void processTextMessage(WebSocketSession webSocketSession, WsSession session, TextMessage message) {
		try {
			log.info(
				"ws inbound sessionId={} userId={} roomId={} payloadSize={}",
				webSocketSession.getId(),
				session.getUserId(),
				session.getRoomId(),
				message.getPayloadLength()
			);
			commandDispatchService.handleMessage(session, envelopeValidator.validate(message.getPayload()));
		} catch (InvalidEnvelopeException exception) {
			handleInvalidEnvelope(webSocketSession, exception);
		} catch (EngineRequestRejectedException exception) {
			handleEngineRejected(webSocketSession, exception);
		}
	}

	private void handleInvalidEnvelope(WebSocketSession session, InvalidEnvelopeException exception) {
		log.warn("ws invalid envelope sessionId={} error={}", session.getId(), exception.getMessage());
		sendError(session, 901, "INVALID_PAYLOAD", null);
	}

	private void handleEngineRejected(WebSocketSession session, EngineRequestRejectedException exception) {
		log.warn("ws engine rejected sessionId={} reason={} error={}", session.getId(), exception.getReason(), exception.getMessage());
		switch (exception.getReason()) {
			case NOT_OWNER -> sendError(session, 902, "ROOM_NOT_FOUND", null);
			case MIGRATING -> sendError(session, 909, "MIGRATING", null);
			default -> sendError(session, 909, "INTERNAL_ERROR", null);
		}
	}

	private void sendError(WebSocketSession session, int code, String message, String rid) {
		try {
			log.info("ws outbound error sessionId={} code={} message={} rid={}", session.getId(), code, message, rid);
			ObjectNode p = objectMapper.createObjectNode();
			p.put("code", code);
			p.put("message", message);
			p.putNull("requestEvent");
			p.set("details", objectMapper.createObjectNode());
			ServerEnvelope envelope = new ServerEnvelope(3, p, rid);
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
		} catch (Exception exception) {
			log.warn("ws error send failed sessionId={} code={} cause={}", session.getId(), code, exception.getMessage());
			closeQuietly(session, CloseStatus.SERVER_ERROR);
		}
	}

	private void closeQuietly(WebSocketSession session, CloseStatus closeStatus) {
		try {
			if (session.isOpen()) {
				session.close(closeStatus);
			}
		} catch (Exception ignored) {
		}
	}
}
