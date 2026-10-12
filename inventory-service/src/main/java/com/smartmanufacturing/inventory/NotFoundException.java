package com.smartmanufacturing.inventory;
/** 资源不存在：按契约输出 404，type 为 about:blank（不属于业务错误枚举）。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String detail) { super(detail); }
}
