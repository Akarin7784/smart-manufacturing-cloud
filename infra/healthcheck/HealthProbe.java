import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 容器健康探针：使用 JDK 自带 HttpClient 请求 Actuator 就绪端点。
 * <p>
 * 设计原因：运行镜像只包含 JRE，不安装 curl/wget，避免扩大攻击面和镜像体积；
 * 该类在构建阶段编译，运行时以 {@code java -cp /app/probe HealthProbe <url>} 调用。
 * 退出码 0 表示就绪，1 表示未就绪或请求失败，供 Docker Compose healthcheck 判断。
 */
public final class HealthProbe {

    /** 工具类不允许实例化。 */
    private HealthProbe() {
    }

    /**
     * 探测入口。
     *
     * @param args 第一个参数为就绪端点地址，缺省为本机 8080 端口的 readiness 端点
     */
    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/actuator/health/readiness";
        try {
            // 连接与读取均设置短超时，避免探针挂起超过 compose 的 timeout 而被强制终止。
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // Actuator 在未就绪时返回 503；同时校验状态体中的 UP，防止代理等返回误判的 200。
            boolean ready = response.statusCode() == 200 && response.body().contains("\"UP\"");
            System.exit(ready ? 0 : 1);
        } catch (Exception e) {
            // 服务尚未监听端口等异常都视为未就绪，由 compose 按 retries 重试。
            System.err.println("health probe failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
