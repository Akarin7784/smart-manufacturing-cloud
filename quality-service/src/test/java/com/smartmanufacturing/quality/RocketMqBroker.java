package com.smartmanufacturing.quality;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * 集成测试用真实 RocketMQ：同一容器内启动 NameServer 与单主 Broker（remoting 协议，与 compose 的 namesrv + broker 部署一致，
 * 镜像版本取 compose 默认的 apache/rocketmq:5.5.0），整个测试 JVM 共享一个实例，由 Ryuk 在结束时清理。
 * <p>
 * Broker 向 NameServer 注册的是 brokerIP1:listenPort，客户端按此地址直连 Broker，所以 Broker 端口必须宿主机与容器内一致：
 * 选一个空闲端口同时作为容器内 listenPort 和宿主机映射端口（固定映射），brokerIP1 填 Docker 主机地址。
 * Topic 由 mqadmin 显式创建（与 infra/rocketmq/topics.txt 的做法一致，关闭自动建 Topic）。
 */
final class RocketMqBroker {
    static final String IMAGE = "apache/rocketmq:5.5.0";
    private static GenericContainer<?> container;
    private static String nameServer;

    private RocketMqBroker() {}

    /** 启动（若尚未启动）并返回 NameServer 地址 host:port。 */
    static synchronized String nameServer() {
        if (container == null) start();
        return nameServer;
    }

    @SuppressWarnings({"deprecation", "resource"})
    private static void start() {
        int brokerPort = freePort();
        int namesrvPort = freePort();
        String host = DockerClientFactory.instance().dockerHostIpAddress();
        if ("localhost".equals(host)) host = "127.0.0.1";
        String conf = String.join("\n",
            "brokerClusterName = DefaultCluster",
            "brokerName = broker-it",
            "brokerId = 0",
            "brokerRole = ASYNC_MASTER",
            "flushDiskType = ASYNC_FLUSH",
            "brokerIP1 = " + host,
            "listenPort = " + brokerPort,
            "autoCreateTopicEnable = false",
            "autoCreateSubscriptionGroup = true",
            "mappedFileSizeCommitLog = 67108864",
            "mappedFileSizeConsumeQueue = 300000",
            "");
        GenericContainer<?> c = new FixedHostPortGenericContainer<>(IMAGE)
            .withFixedExposedPort(namesrvPort, 9876)
            .withFixedExposedPort(brokerPort, brokerPort)
            .withEnv("JAVA_OPT_EXT", "-Xms128m -Xmx256m -Xmn64m -XX:MaxDirectMemorySize=256m")
            .withCopyToContainer(Transferable.of(conf), "/home/rocketmq/broker-it.conf")
            .withCommand("sh", "-c", "$ROCKETMQ_HOME/bin/mqnamesrv & $ROCKETMQ_HOME/bin/mqbroker -n localhost:9876 -c /home/rocketmq/broker-it.conf")
            .waitingFor(Wait.forLogMessage(".*boot success.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        c.start();
        container = c;
        nameServer = host + ":" + namesrvPort;
        createTopic("mes-workorder-quality");
        createTopic("mes-quality-event");
    }

    /** 用容器内 mqadmin 创建 Topic；Broker 刚启动时注册可能尚未完成，失败重试。 */
    private static void createTopic(String topic) {
        for (int attempt = 1; ; attempt++) {
            try {
                Container.ExecResult r = container.execInContainer("sh", "-c",
                    "JAVA_OPT_EXT='-Xms32m -Xmx128m -Xmn16m' $ROCKETMQ_HOME/bin/mqadmin updateTopic -n localhost:9876 -c DefaultCluster -t "
                        + topic + " -r 4 -w 4");
                if (r.getExitCode() == 0 && r.getStdout().contains("success")) return;
                if (attempt >= 20) throw new IllegalStateException("创建 Topic 失败：" + r.getStdout() + r.getStderr());
                Thread.sleep(1000);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("创建 Topic 失败", e);
            }
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
