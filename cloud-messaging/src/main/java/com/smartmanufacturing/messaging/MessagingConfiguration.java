package com.smartmanufacturing.messaging;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
/** 统一绑定消息配置，客户端实例由生产者或消费者服务按启用开关创建。 */
@Configuration
@EnableConfigurationProperties(RocketMqProperties.class)
public class MessagingConfiguration {}
