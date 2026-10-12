package com.smartmanufacturing.device;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
/** 事务上下文是线程变量，必须验证成功和失败路径都不会污染后续请求。 */
class SeataContextFilterTest {
    /** 请求抛异常后仍恢复原上下文，避免 Servlet 线程复用串联其他事务。 */
    @Test void restoresContextAfterFailure() throws Exception {
        var request=new MockHttpServletRequest();request.setServletPath("/devices/reservations");
        request.addHeader(RootContext.KEY_XID,"127.0.0.1:8091:1234");
        RootContext.bind("previous");
        try{
            assertThrows(jakarta.servlet.ServletException.class,()->new SeataContextFilter(true).doFilter(request,new MockHttpServletResponse(),
                (req,res)->{assertEquals("127.0.0.1:8091:1234",RootContext.getXID());throw new jakarta.servlet.ServletException("failure");}));
            assertEquals("previous",RootContext.getXID());
        }finally{RootContext.unbind();}
    }
    /** 普通设备查询不绑定外部传来的 XID，保持事务传播范围最小化。 */
    @Test void ordinaryReadDoesNotBindXid() throws Exception {
        var request=new MockHttpServletRequest();request.setServletPath("/devices/D001");
        request.addHeader(RootContext.KEY_XID,"forged");
        new SeataContextFilter(true).doFilter(request,new MockHttpServletResponse(),(req,res)->assertNull(RootContext.getXID()));
    }
}
