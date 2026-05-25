package com.example.epub.core.service.epub;
public final class EpubFailure extends RuntimeException {
    public enum Reason {INVALID_INPUT,NOT_FOUND,REVISION_CONFLICT,IDEMPOTENCY_CONFLICT,TOO_LARGE,UNSUPPORTED,QUOTA_EXCEEDED,INVALID_STATE,STORAGE_UNAVAILABLE}
    private final Reason reason;
    public EpubFailure(Reason reason) {super(reason.name());this.reason=reason;}
    public Reason reason() {return reason;}
}
