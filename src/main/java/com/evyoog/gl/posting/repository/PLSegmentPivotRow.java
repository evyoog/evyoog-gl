package com.evyoog.gl.posting.repository;

import java.math.BigDecimal;

public interface PLSegmentPivotRow {
    String getNaturalAccountCode();
    String getNaturalAccountName();
    String getAccountQualifier();
    String getSegmentValue();
    BigDecimal getPtdDr();
    BigDecimal getPtdCr();
}
