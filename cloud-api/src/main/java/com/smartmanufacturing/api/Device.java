package com.smartmanufacturing.api;
/** 跨服务传递的设备信息；状态使用 ONLINE 或 OFFLINE。 */
public record Device(String id, String name, String status) {}
