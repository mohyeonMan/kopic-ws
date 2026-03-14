package io.jhpark.kopic.ws.session.domain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.socket.WebSocketSession;

@Getter
@Setter
@AllArgsConstructor
public class WsSession {

	private WebSocketSession webSocketSession;
	private String userId;
	private String roomId;
	private Instant connectedAt;
	private Instant lastSeenAt;
}
