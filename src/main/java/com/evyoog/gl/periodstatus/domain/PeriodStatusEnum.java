package com.evyoog.gl.periodstatus.domain;

public enum PeriodStatusEnum {
    NOT_OPENED,
    FUTURE_ENTERABLE,
    OPEN,
    CLOSED,
    LOCKED,
    /** Terminal — set via {@code PeriodManagementService} once a CLOSED period is finalised. Never reopenable. */
    PERMANENTLY_CLOSED
}
