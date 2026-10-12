package com.smartmanufacturing.messaging;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 消息组件配置；默认关闭客户端连接，普通本地开发不要求启动 Broker。 */
@Validated
@ConfigurationProperties("messaging.rocketmq")
public class RocketMqProperties {
    private boolean enabled;
    @NotBlank private String nameServer = "localhost:9876";
    @NotBlank private String topic = "manufacturing-workorders";
    @NotBlank private String producerGroup = "manufacturing-workorder-producer";
    @NotBlank private String consumerGroup = "manufacturing-workorder-events";
    @Min(500) @Max(10000) private int sendTimeoutMs = 3000;
    @Min(1) @Max(100) private int batchSize = 10;
    @Min(1) @Max(20) private int maxAttempts = 8;
    @Min(15) @Max(3600) private int leaseSeconds = 60;
    @Min(1) @Max(3600) private int retryBaseSeconds = 2;
    @Min(500) private long pollIntervalMs = 2000;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNameServer() { return nameServer; }
    public void setNameServer(String nameServer) { this.nameServer = nameServer; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getProducerGroup() { return producerGroup; }
    public void setProducerGroup(String producerGroup) { this.producerGroup = producerGroup; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    public int getSendTimeoutMs() { return sendTimeoutMs; }
    public void setSendTimeoutMs(int sendTimeoutMs) { this.sendTimeoutMs = sendTimeoutMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public int getRetryBaseSeconds() { return retryBaseSeconds; }
    public void setRetryBaseSeconds(int retryBaseSeconds) { this.retryBaseSeconds = retryBaseSeconds; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
}
