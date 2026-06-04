package com.ob.cln.kyc.si.chart;

public record W4ComparisonResult(
    Status status,
    String detail
) {
    public enum Status { MATCH, MISMATCH, LLM_FAILED, CODE_FAILED }
}
