/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package integration;

import example.ZipkinStreamServerApplication;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.boot.test.WebIntegrationTest;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.stream.SleuthSink;
import org.springframework.cloud.sleuth.stream.SleuthSource;
import org.springframework.cloud.sleuth.stream.Spans;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kafka.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.KafkaProducerProperties;
import org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfigurationProperties;
import org.springframework.cloud.stream.test.junit.kafka.KafkaTestSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.MessageEndpoint;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import tools.AbstractIntegrationTest;

import java.util.Arrays;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = { ZipkinStreamServerApplication.class, ZipkinStreamKafkaTests.Config.class })
@WebIntegrationTest({ "server.port=0", "management.health.rabbit.enabled=false" })
@ActiveProfiles("kafka")
public class ZipkinStreamKafkaTests extends AbstractIntegrationTest {

	@ClassRule
	public static KafkaTestSupport kafkaTestSupport = new KafkaTestSupport(true);

	KafkaTestBinder binder;

	@Autowired Tracer tracer;
	@Autowired SpanMessageListener spanMessageListener;
	@Autowired @Qualifier(SleuthSink.INPUT) MessageChannel input;
	@Autowired @Qualifier(SleuthSource.OUTPUT) MessageChannel output;

	@Test
	public void should_propagate_spans_to_zipkin() {
		KafkaTestBinder binder = getBinder();
		Binding<MessageChannel> producerBinding = binder.bindProducer("sleuth", output, createProducerProperties());
		Binding<MessageChannel> consumerBinding = binder.bindConsumer("sleuth", "sleuth", input, createConsumerProperties());
		Span span = tracer.createSpan("new_span");

		tracer.close(span);

		await().until(() -> !spanMessageListener.spans.isEmpty());
		producerBinding.unbind();
		consumerBinding.unbind();
	}

	protected KafkaTestBinder getBinder() {
		if (binder == null) {
			binder = new KafkaTestBinder(kafkaTestSupport, new KafkaBinderConfigurationProperties());
		}
		return binder;
	}

	protected ExtendedConsumerProperties<KafkaConsumerProperties> createConsumerProperties() {
		return new ExtendedConsumerProperties<>(new KafkaConsumerProperties());
	}

	protected ExtendedProducerProperties<KafkaProducerProperties> createProducerProperties() {
		return new ExtendedProducerProperties<>(new KafkaProducerProperties());
	}

	@Override
	protected String getAppName() {
		return "local";
	}

	@Configuration
	public static class Config {
		@Bean
		SpanMessageListener spanMessageListener() {
			return new SpanMessageListener();
		}

		@Bean
		KafkaBinderConfigurationProperties kafkaBinderConfigurationProperties() {
			KafkaBinderConfigurationProperties kafkaBinderConfigurationProperties = new KafkaBinderConfigurationProperties();
			kafkaBinderConfigurationProperties.setDefaultZkPort(Arrays.asList(kafkaTestSupport.getZkConnectString().split(":")).get(1));
			return kafkaBinderConfigurationProperties;
		}
	}

	@MessageEndpoint
	public static class SpanMessageListener {

		Queue<Message<Spans>> spans = new LinkedBlockingQueue<>();

		@ServiceActivator(inputChannel = SleuthSink.INPUT)
		public void sink(Message<Spans> input) {
			Optional<Span> newSpan = input.getPayload().getSpans().stream().filter((Span span) -> span.getName().equals("new_span")).findFirst();
			if(newSpan.isPresent()) {
				spans.add(input);
			}
		}
	}

}
