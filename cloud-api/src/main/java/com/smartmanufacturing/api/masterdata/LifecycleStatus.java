package com.smartmanufacturing.api.masterdata;
/** 版本化主数据生命周期：草稿可修改；发布后只读，只能通过新版本替代；作废不再参与生效判断。 */
public enum LifecycleStatus { DRAFT, RELEASED, OBSOLETE }
