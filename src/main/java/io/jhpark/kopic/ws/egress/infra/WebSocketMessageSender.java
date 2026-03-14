package io.jhpark.kopic.ws.egress.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ws.egress.app.SessionDeliveryPort;
import io.jhpark.kopic.ws.egress.dto.ServerEnvelope;
import io.jhpark.kopic.ws.session.app.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;

@Component
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
				return;
			}

			try {
				session.getWebSocketSession().sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
			} catch (Exception exception) {
				throw new IllegalStateException("failed to send websocket message", exception);
			}
		});
	}
}
