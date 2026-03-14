package io.jhpark.kopic.ws.ingress.infra;

import io.jhpark.kopic.ws.config.WebSocketProperties;
import io.jhpark.kopic.ws.dispatch.app.CommandDispatchService;
import io.jhpark.kopic.ws.dispatch.app.EngineRequestRejectedException;
import io.jhpark.kopic.ws.ingress.app.EnvelopeValidator;
import io.jhpark.kopic.ws.ingress.app.InvalidEnvelopeException;
import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;
import io.jhpark.kopic.ws.session.app.SessionRegistry;
import io.jhpark.kopic.ws.session.domain.WsSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
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
		try {
			commandDispatchService.handleConnected(wsSession);
		} catch (EngineRequestRejectedException exception) {
			sessionRegistry.remove(session.getId());
			closeQuietly(session, CloseStatus.SERVER_ERROR);
		}
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		sessionRegistry.touch(session.getId(), Instant.now()).ifPresent(touchedSession -> {
			try {
				commandDispatchService.handleMessage(touchedSession, envelopeValidator.validate(message.getPayload()));
			} catch (InvalidEnvelopeException exception) {
				sendError(session, 901, "INVALID_PAYLOAD", null);
			} catch (EngineRequestRejectedException exception) {
				sendError(session, 909, "ENGINE_REJECTED", null);
			}
		});
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
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

	private void sendError(WebSocketSession session, int code, String message, String requestId) {
		try {
			ObjectNode payload = objectMapper.createObjectNode();
			payload.put("code", code);
			payload.put("message", message);
			payload.putNull("requestEvent");
			payload.set("details", objectMapper.createObjectNode());
			ServerEnvelope envelope = new ServerEnvelope(3, payload, requestId);
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
		} catch (Exception exception) {
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
