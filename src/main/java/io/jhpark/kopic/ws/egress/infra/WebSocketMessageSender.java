package io.jhpark.kopic.ws.egress.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.egress.app.SessionDeliveryPort;
import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;
import io.jhpark.kopic.ws.session.app.SessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

@Component
@Slf4j
public class WebSocketMessageSender implements SessionDeliveryPort {

	private final ObjectMapper objectMapper;
	private final SessionRegistry sessionRegistry;

	public WebSocketMessageSender(
		ObjectMapper objectMapper,
		SessionRegistry sessionRegistry
	) {
		this.objectMapper = objectMapper;
		this.sessionRegistry = sessionRegistry;
	}

	@Override
	public void deliver(String userId, ServerEnvelope event) {
		sessionRegistry.findCurrentByUserId(userId).ifPresent(session -> {
			if (!session.getWebSocketSession().isOpen()) {
				log.warn("ws outbound skipped closed session userId={} e={} rid={}", userId, event.e(), event.rid());
				return;
			}

			try {
				log.info("ws outbound userId={} sessionId={} e={} rid={}", userId, session.getWebSocketSession().getId(), event.e(), event.rid());
				session.getWebSocketSession().sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
			} catch (Exception exception) {
				log.warn("ws outbound failed userId={} sessionId={} e={} cause={}", userId, session.getWebSocketSession().getId(), event.e(), exception.getMessage());
				throw new IllegalStateException("failed to send websocket message", exception);
			}
		});
	}
}
