/**
 * 主数据上下文对外契约：物料、工作中心/工位、BOM（版本化、多层展开）与工艺路线。
 * 生产执行等下游服务只通过这些契约与业务编码引用主数据，不访问主数据库。
 */
package com.smartmanufacturing.api.masterdata;
