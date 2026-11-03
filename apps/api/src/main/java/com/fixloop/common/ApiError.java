package com.fixloop.common;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error envelope for every failed request (bible 13.3).
 *
 * @param code    stable machine-readable code, for example {@code VALIDATION_FAILED}
 * @param message human-readable message, safe to display
 * @param traceId opaque request id the user can quote to an administrator
 * @param details optional per-field validation messages, omitted when empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String traceId, Map<String, String> details) {

    /** Convenience constructor for errors that carry no field details. */
    public ApiError(String code, String message, String traceId) {
        this(code, message, traceId, null);
    }
}
