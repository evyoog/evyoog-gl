package com.evyoog.gl.dimension.domain;

public enum DimensionType {
    LEGAL_ENTITY,
    NATURAL_ACCOUNT,
    COST_CENTRE,
    PROFIT_CENTRE,
    INTERCOMPANY,
    PRODUCT,
    PROJECT,
    CUSTOM,
    SPARE   // placeholder/future dimension — distinct from CUSTOM (DEBT-01 short-term fix)
}
