package io.jhpark.kopic.ws.ingress.infra;

import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class RoomHandshakeInterceptor implements HandshakeInterceptor {

	public static final String ATTR_ROOM_ID = "roomId";
	public static final String ATTR_USER_ID = "userId";

	@Override
	public boolean beforeHandshake(
		ServerHttpRequest request,
		ServerHttpResponse response,
		WebSocketHandler wsHandler,
		Map<String, Object> attributes
	) {
		URI uri = request.getURI();
		var queryParams = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
		String roomId = queryParams.getFirst("roomId");
		String userId = queryParams.getFirst("userId");

		if (roomId == null || roomId.isBlank() || userId == null || userId.isBlank()) {
			response.setStatusCode(HttpStatus.BAD_REQUEST);
			return false;
		}

		attributes.put(ATTR_ROOM_ID, roomId);
		attributes.put(ATTR_USER_ID, userId);
		return true;
	}

	@Override
	public void afterHandshake(
		ServerHttpRequest request,
		ServerHttpResponse response,
		WebSocketHandler wsHandler,
		Exception exception
	) {
	}
}
