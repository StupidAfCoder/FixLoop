package com.fixloop.common;

/** Minimal error envelope shared by the security handlers. */
public record ApiError(String code, String message, String traceId) {
}
