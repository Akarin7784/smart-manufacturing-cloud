package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.CreateReservation;
import com.smartmanufacturing.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 验证禁用时拒绝预留，不允许静默退化成无全局事务的双库写入。 */
class ReservationServiceTest {
    /** 关闭功能必须在调用远程服务或写库之前失败。 */
    @Test void disabledSeataRejectsReservationBeforeRemoteCall() {
        var client=mock(ReservationDeviceClient.class);
        var service=new ReservationService(client,mock(JdbcTemplate.class),mock(TransactionTemplate.class),false);
        var error=assertThrows(BusinessException.class,()->service.reserve(new CreateReservation("r1","D001")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
        verifyNoInteractions(client);
    }
    /** 关闭功能也不能执行跨库释放，保持已存在预留的状态。 */
    @Test void disabledSeataRejectsRelease() {
        var client=mock(ReservationDeviceClient.class);
        var service=new ReservationService(client,mock(JdbcTemplate.class),mock(TransactionTemplate.class),false);
        assertThrows(BusinessException.class,()->service.release("r1"));
        verifyNoInteractions(client);
    }

    /** 下游冲突、离线与服务故障必须保留失败状态，且不执行本地写入。 */
    @Test void remoteFailuresKeepStatusAndDoNotWriteLocally() {
        var client=mock(ReservationDeviceClient.class);
        var jdbc=mock(JdbcTemplate.class);
        var tx=mock(TransactionTemplate.class);
        var service=new ReservationService(client,jdbc,tx,true);
        var request=new CreateReservation("r1","D001");
        var http=feign.Request.create(feign.Request.HttpMethod.POST,"/devices/reservations",java.util.Map.of(),null,
            java.nio.charset.StandardCharsets.UTF_8,null);
        for(int code:new int[]{401,403,404,409,503}){
            // doThrow 避免重新配置同一方法时触发上一轮已经安装的异常桩。
            doThrow(feign.FeignException.errorStatus("reserve",
                feign.Response.builder().status(code).reason("failure").request(http).headers(java.util.Map.of()).build()))
                .when(client).reserve(request);
            var error=assertThrows(BusinessException.class,()->service.reserve(request));
            assertEquals(code,error.status().value());
        }
        verifyNoInteractions(jdbc,tx);
    }
}
