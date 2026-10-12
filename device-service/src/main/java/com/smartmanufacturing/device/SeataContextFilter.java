package com.smartmanufacturing.device;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.apache.seata.core.context.RootContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** 仅在设备预留分支传播事务上下文，结束时清理线程变量以免复用污染。 */
@Component
public class SeataContextFilter extends OncePerRequestFilter {
    private final boolean enabled;
    public SeataContextFilter(@Value("${seata.enabled:false}")boolean enabled){this.enabled=enabled;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return !enabled || !request.getServletPath().startsWith("/devices/reservations");
    }
    /** XID 不是身份凭据；请求仍必须通过 Spring Security，TC 再校验事务有效性。 */
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws ServletException,IOException {
        String xid=req.getHeader(RootContext.KEY_XID);
        if(xid!=null && (xid.length()>128 || xid.contains("\n") || xid.contains("\r"))){res.sendError(400);return;}
        String previous=RootContext.getXID();
        if(xid!=null) RootContext.bind(xid);
        try{chain.doFilter(req,res);}finally{RootContext.unbind();if(previous!=null)RootContext.bind(previous);}
    }
}
