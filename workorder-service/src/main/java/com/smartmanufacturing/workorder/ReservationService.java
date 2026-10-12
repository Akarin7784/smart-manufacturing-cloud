package com.smartmanufacturing.workorder;
import com.smartmanufacturing.api.*;
import com.smartmanufacturing.common.BusinessException;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
/** 预留单与远程设备预留由 Seata AT 协调，避免将未全局提交的数据发到 MQ。 */
@Service
public class ReservationService {
    private final ReservationDeviceClient devices;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate local;
    private final boolean enabled;
    public ReservationService(ReservationDeviceClient devices,JdbcTemplate jdbc,TransactionTemplate local,
            @Value("${seata.enabled:false}")boolean enabled){
        this.devices=devices;this.jdbc=jdbc;this.local=local;this.enabled=enabled;
    }
    /** 先提交远程设备分支，再写本地单据；本地编号冲突可真实验证远程分支回滚。 */
    @GlobalTransactional(name="reserve-device",timeoutMills=30000,rollbackFor=Exception.class)
    public Reservation reserve(CreateReservation request){
        requireEnabled();
        var response=callDevice(()->devices.reserve(request));
        if(response==null || !"OK".equals(response.code()) || response.data()==null)
            throw new BusinessException("RESERVATION_FAILED","设备预留失败",HttpStatus.SERVICE_UNAVAILABLE);
        try{
            local.executeWithoutResult(status->jdbc.update("insert into work_order_reservation (reservation_id,device_id) values (?,?)",
                request.reservationId(),request.deviceId()));
        }catch(DuplicateKeyException e){
            throw new BusinessException("RESERVATION_EXISTS","预留单编号已存在",HttpStatus.CONFLICT);
        }
        return new Reservation(request.reservationId(),request.deviceId());
    }
    /** 两个服务的删除也受全局事务保护，重复释放可以安全返回成功。 */
    @GlobalTransactional(name="release-device",timeoutMills=30000,rollbackFor=Exception.class)
    public String release(String id){
        requireEnabled();
        var result=callDevice(()->devices.release(id));
        if(result==null || !"OK".equals(result.code()))throw new BusinessException("RESERVATION_FAILED","设备释放失败",HttpStatus.SERVICE_UNAVAILABLE);
        local.executeWithoutResult(status->jdbc.update("delete from work_order_reservation where reservation_id=?",id));
        return id;
    }
    private void requireEnabled(){
        if(!enabled)throw new BusinessException("SEATA_DISABLED","请启用 Seata 后使用预留流程",HttpStatus.SERVICE_UNAVAILABLE);
    }
    /** 保留远程业务冲突和权限语义，连接故障仍抛出异常以触发全局回滚。 */
    private <T> T callDevice(java.util.function.Supplier<T> action){
        try{return action.get();}
        catch(feign.FeignException e){
            HttpStatus status=switch(e.status()){
                case 400 -> HttpStatus.BAD_REQUEST;
                case 401 -> HttpStatus.UNAUTHORIZED;
                case 403 -> HttpStatus.FORBIDDEN;
                case 404 -> HttpStatus.NOT_FOUND;
                case 409 -> HttpStatus.CONFLICT;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new BusinessException("DEVICE_RESERVATION_REJECTED","设备预留服务拒绝操作或暂时不可用",status);
        }
    }
}
