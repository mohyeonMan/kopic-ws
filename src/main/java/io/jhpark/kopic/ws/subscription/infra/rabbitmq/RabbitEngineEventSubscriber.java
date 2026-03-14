package io.jhpark.kopic.ws.subscription.infra.rabbitmq;

import io.jhpark.kopic.ws.egress.app.SessionDeliveryPort;
import io.jhpark.kopic.ws.subscription.app.EngineEventSubscriber;
import io.jhpark.kopic.ws.subscription.app.InboundEngineEvent;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RabbitEngineEventSubscriber implements EngineEventSubscriber {

	private final SessionDeliveryPort sessionDeliveryPort;

	public RabbitEngineEventSubscriber(SessionDeliveryPort sessionDeliveryPort) {
		this.sessionDeliveryPort = sessionDeliveryPort;
	}

	@Override
	public void handle(InboundEngineEvent event) {
		sessionDeliveryPort.deliver(event.userId(), event.envelope());
	}

	@RabbitListener(
		queues = "#{@rabbitNodeQueue.name}",
		containerFactory = "rabbitListenerContainerFactory"
	)
	public void receive(InboundEngineEvent event) {
		handle(event);
	}
}
