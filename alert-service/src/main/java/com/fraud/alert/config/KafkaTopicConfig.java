package com.fraud.alert.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic fraudDetectedTopic(@Value("${app.kafka.topics.fraud-detected}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic fraudDetectedDlqTopic(@Value("${app.kafka.topics.fraud-detected}") String topic) {
        return TopicBuilder.name(topic + ".dlq")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
