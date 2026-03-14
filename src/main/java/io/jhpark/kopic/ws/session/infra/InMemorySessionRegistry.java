package io.jhpark.kopic.ws.session.infra;

import io.jhpark.kopic.ws.session.app.SessionRegistry;
import io.jhpark.kopic.ws.session.domain.WsSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemorySessionRegistry implements SessionRegistry {

	private final ConcurrentHashMap<String, WsSession> sessionsByUserId = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> userIdBySessionId = new ConcurrentHashMap<>();

	@Override
	public List<WsSession> findByUserId(String userId) {
		return Optional.ofNullable(sessionsByUserId.get(userId))
			.stream()
			.toList();
	}

	@Override
	public Optional<WsSession> findCurrentByUserId(String userId) {
		return Optional.ofNullable(sessionsByUserId.get(userId));
	}

	@Override
	public Optional<WsSession> findBySessionId(String sessionId) {
		return Optional.ofNullable(userIdBySessionId.get(sessionId))
			.map(sessionsByUserId::get);
	}

	@Override
	public void save(WsSession session) {
		sessionsByUserId.put(session.getUserId(), session);
		userIdBySessionId.put(session.getWebSocketSession().getId(), session.getUserId());
	}

	@Override
	public Optional<WsSession> touch(String sessionId, Instant touchedAt) {
		return findBySessionId(sessionId).map(session -> {
			session.setLastSeenAt(touchedAt);
			return session;
		});
	}

	@Override
	public void remove(String sessionId) {
		String userId = userIdBySessionId.remove(sessionId);
		if (userId != null) {
			sessionsByUserId.remove(userId);
		}
	}
}
