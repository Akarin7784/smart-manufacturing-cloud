package com.smartmanufacturing.api;

import java.util.List;

/** 通用分页结果：items 为当前页数据，page 从 0 开始，total 为满足条件的总条数。 */
public record PageView<T>(List<T> items, int page, int size, long total) {}
