package com.example.epub.core.service.catalog;
import java.util.Map;
public class CatalogFailure extends RuntimeException {
    public enum Reason { RATE_LIMITED,INVALID_REQUEST, NOT_FOUND, REVISION_CONFLICT, IDEMPOTENCY_CONFLICT, CATEGORY_IN_USE, REFERENCE_CONFLICT, INVALID_STATE }
    private final Reason reason;
    private final Map<String,String> fields;
    public CatalogFailure(Reason reason) {this(reason,Map.of());}
    public CatalogFailure(Reason reason,Map<String,String> fields) {super(reason.name());this.reason=reason;this.fields=Map.copyOf(fields);}
    public Reason reason() {return reason;}
    public Map<String,String> fields() {return fields;}
    public static CatalogFailure invalid(String field,String message) {return new CatalogFailure(Reason.INVALID_REQUEST,Map.of(field,message));}
}
