package io.jhpark.kopic.ws.dispatch.app;

import io.jhpark.kopic.ws.ingress.dto.ClientEnvelope;
import io.jhpark.kopic.ws.session.domain.WsSession;

public interface CommandDispatchService {

	void handleConnected(WsSession session);

	void handleMessage(WsSession session, ClientEnvelope envelope);

	void handleDisconnected(WsSession session);
}
