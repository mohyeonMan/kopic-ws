package io.jhpark.kopic.ws.session.app;

import io.jhpark.kopic.ws.session.domain.WsSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SessionRegistry {

	List<WsSession> findByUserId(String userId);

	Optional<WsSession> findCurrentByUserId(String userId);

	Optional<WsSession> findBySessionId(String sessionId);

	void save(WsSession session);

	Optional<WsSession> touch(String sessionId, Instant touchedAt);

	void remove(String sessionId);
}
