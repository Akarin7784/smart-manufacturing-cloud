package com.smartmanufacturing.workorder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * outbox 中继端到端（真实 MySQL 8.4 + 真实 RocketMQ 5.5.0，均为 Testcontainers）：
 * 带不良数的报工 → 中继投递 → 消费者从 mes-workorder-quality 实际收到 DefectReported（eventId 与 outbox 一致）→ 待投递 Gauge 归零。
 * <p>RocketMQ 容器内同时运行 NameServer 与 Broker。Broker 向 NameServer 注册的地址必须能被宿主机上的客户端直接访问，
 * 因此预先选取两个空闲宿主端口，并让容器内监听端口与宿主端口一致（brokerIP1 = 127.0.0.1）。只启动本测试自己的容器。
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OutboxRelayRocketMqIT {
    private static final int NAMESRV_PORT = freePort();
    private static final int BROKER_PORT = freePort();

    @Container @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> ROCKETMQ = new GenericContainer<>("apache/rocketmq:5.5.0")
        .withExposedPorts(NAMESRV_PORT, BROKER_PORT)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(NAMESRV_PORT), new ExposedPort(NAMESRV_PORT)),
            new PortBinding(Ports.Binding.bindPort(BROKER_PORT), new ExposedPort(BROKER_PORT))))
        .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx256m -Xmn128m")
        .withCopyToContainer(Transferable.of(("brokerClusterName=DefaultCluster\nbrokerName=broker-it\nbrokerId=0\n"
            + "brokerIP1=127.0.0.1\nlistenPort=" + BROKER_PORT + "\nnamesrvAddr=127.0.0.1:" + NAMESRV_PORT + "\n"
            + "autoCreateTopicEnable=false\nflushDiskType=ASYNC_FLUSH\n").getBytes(StandardCharsets.UTF_8)), "/tmp/broker-it.conf")
        .withCommand("sh", "-c", "export NAMESRV_ADDR=127.0.0.1:" + NAMESRV_PORT
            + "; printf 'listenPort=" + NAMESRV_PORT + "\\n' > /tmp/namesrv-it.conf"
            + "; sh mqnamesrv -c /tmp/namesrv-it.conf & sleep 3; sh mqbroker -c /tmp/broker-it.conf")
        .waitingFor(Wait.forLogMessage(".*boot success.*", 2).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void rocketmq(DynamicPropertyRegistry r) { r.add("rocketmq.name-server", () -> "127.0.0.1:" + NAMESRV_PORT); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired WorkOrderReleaseService release;
    @Autowired WorkOrderOutboxRelay relay;
    @Autowired LocalMaterialReservation stub;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @MockitoBean MasterdataClient masterdata;
    @MockitoBean DeviceClient devices;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    private DefaultMQPushConsumer consumer;
    private final BlockingQueue<MessageExt> received = new LinkedBlockingQueue<>();

    @BeforeAll static void createTopics() throws Exception {
        // 宿主端口必须与容器内监听端口一致，否则 Broker 注册的地址宿主机无法访问
        assertThat(ROCKETMQ.getMappedPort(NAMESRV_PORT)).isEqualTo(NAMESRV_PORT);
        assertThat(ROCKETMQ.getMappedPort(BROKER_PORT)).isEqualTo(BROKER_PORT);
        // Broker 关闭了自动建 Topic（与 compose 部署一致），先显式创建本测试用到的两个 Topic
        // Broker 启动日志出现后仍可能尚未注册到 NameServer（mqadmin 此时输出 [error] 但退出码为 0），因此重试直到成功
        for (String topic : List.of("mes-workorder-quality", "mes-workorder-event")) {
            String out = "";
            for (int i = 0; i < 30 && !out.contains("success"); i++) {
                var r = ROCKETMQ.execInContainer("sh", "mqadmin", "updateTopic", "-n", "127.0.0.1:" + NAMESRV_PORT,
                    "-c", "DefaultCluster", "-t", topic, "-r", "4", "-w", "4");
                out = r.getStdout() + r.getStderr();
                if (!out.contains("success")) Thread.sleep(1000);
            }
            assertThat(out).as("创建 Topic %s", topic).contains("success");
        }
    }

    @BeforeEach void setUp() throws Exception {
        ReleaseFixtures.clear(jdbc); ReleaseFixtures.stubMasterdata(masterdata); stub.reset();
        consumer = new DefaultMQPushConsumer("CG_QUALITY_DEFECT_IT");
        consumer.setNamesrvAddr("127.0.0.1:" + NAMESRV_PORT);
        consumer.setInstanceName("it-" + UUID.randomUUID());
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe("mes-workorder-quality", "DefectReported");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            received.addAll(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
    }

    @AfterEach void tearDown() { if (consumer != null) consumer.shutdown(); }

    @Test void defectReportedIsDeliveredAndPendingGaugeDrains() throws Exception {
        String id = ReleaseFixtures.createOrder(jdbc, 10);
        release.release(id, "rel-" + id, "planner");
        mvc.perform(post("/workorders/" + id + "/operations/OP10/reports")
                .with(jwt().authorities(new SimpleGrantedAuthority("workorder:write"), new SimpleGrantedAuthority("workorder:read")))
                .header("Idempotency-Key", "k-" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodQty\":\"7\",\"defectQty\":\"2.5\",\"scrapQty\":\"0\",\"uom\":\"PCS\",\"lotNo\":\"LOT-9\"}"))
            .andReturn();
        String defectEventId = jdbc.queryForObject("select event_id from outbox_event where aggregate_id = ? and event_type = 'DefectReported'", String.class, id);

        // 中继投递：首次发送可能因路由尚未拉取而失败（随后按退避重试），因此轮询直到没有待投递行
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (pending() > 0 && System.nanoTime() < deadline) {
            relay.publishBatch();
            if (pending() > 0) Thread.sleep(500);
        }
        assertThat(pending()).as("待投递事件应全部投递").isZero();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and status = 'SENT' and published_at is not null", Integer.class, id))
            .isEqualTo(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Integer.class, id));

        MessageExt msg = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < until) {
            MessageExt m = received.poll(1, TimeUnit.SECONDS);
            if (m != null && defectEventId.equals(m.getUserProperty("eventId"))) { msg = m; break; }
        }
        assertThat(msg).as("消费者应从 mes-workorder-quality 收到 DefectReported").isNotNull();
        assertThat(msg.getTopic()).isEqualTo("mes-workorder-quality");
        assertThat(msg.getTags()).isEqualTo("DefectReported");
        assertThat(msg.getKeys()).isEqualTo(id);
        JsonNode body = mapper.readTree(new String(msg.getBody(), StandardCharsets.UTF_8));
        assertThat(body.get("eventId").asText()).isEqualTo(defectEventId);
        assertThat(body.get("workOrderNo").asText()).isEqualTo(id);
        assertThat(body.get("defectQty").asText()).isEqualTo("2.500000");
        assertThat(body.get("lotNo").asText()).isEqualTo("LOT-9");

        assertThat(meters.get("mes.workorder.outbox.pending").gauge().value()).isZero();
    }

    private long pending() {
        Long n = jdbc.queryForObject("select count(*) from outbox_event where status = 'NEW'", Long.class);
        return n == null ? 0 : n;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
