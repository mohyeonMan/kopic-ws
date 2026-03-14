package io.jhpark.kopic.ws.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
@EnableConfigurationProperties({ RabbitProperties.class, NodeProperties.class })
public class RabbitConfig {

	@Bean
	public TopicExchange engineOutboundExchange(RabbitProperties rabbitProperties) {
		return new TopicExchange(rabbitProperties.outboundExchange(), true, false);
	}

	@Bean
	public Queue rabbitNodeQueue(
		RabbitProperties rabbitProperties,
		NodeProperties nodeProperties
	) {
		return new Queue(rabbitProperties.queueName(nodeProperties.nodeId()), false, false, true);
	}

	@Bean
	public Binding engineEventBinding(
		Queue rabbitNodeQueue,
		RabbitProperties rabbitProperties,
		NodeProperties nodeProperties,
		TopicExchange engineOutboundExchange
	) {
		return BindingBuilder.bind(rabbitNodeQueue)
			.to(engineOutboundExchange)
			.with(rabbitProperties.routingKey(nodeProperties.nodeId()));
	}

	@Bean
	public Jackson2JsonMessageConverter rabbitJsonMessageConverter() {
		return new Jackson2JsonMessageConverter();
	}

	@Bean
	public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
		ConnectionFactory connectionFactory,
		Jackson2JsonMessageConverter rabbitJsonMessageConverter
	) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setMessageConverter(rabbitJsonMessageConverter);
		return factory;
	}
}
