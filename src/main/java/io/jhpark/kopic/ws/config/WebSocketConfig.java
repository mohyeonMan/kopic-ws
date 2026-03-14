package io.jhpark.kopic.ws.config;

import io.jhpark.kopic.ws.ingress.infra.RoomHandshakeInterceptor;
import io.jhpark.kopic.ws.ingress.infra.WebSocketConnectionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

	private final WebSocketConnectionHandler webSocketConnectionHandler;
	private final RoomHandshakeInterceptor roomHandshakeInterceptor;
	private final WebSocketProperties webSocketProperties;

	public WebSocketConfig(
		WebSocketConnectionHandler webSocketConnectionHandler,
		RoomHandshakeInterceptor roomHandshakeInterceptor,
		WebSocketProperties webSocketProperties
	) {
		this.webSocketConnectionHandler = webSocketConnectionHandler;
		this.roomHandshakeInterceptor = roomHandshakeInterceptor;
		this.webSocketProperties = webSocketProperties;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(webSocketConnectionHandler, webSocketProperties.endpoint())
			.addInterceptors(roomHandshakeInterceptor)
			.setAllowedOriginPatterns("*");
	}
}
