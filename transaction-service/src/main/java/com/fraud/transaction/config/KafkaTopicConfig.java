package com.fraud.transaction.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transactionsCreatedTopic(
            @Value("${app.kafka.topics.transactions-created}") String topic,
            @Value("${app.kafka.partitions:18}") int partitions,
            @Value("${app.kafka.replicas:3}") int replicas
    ) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
