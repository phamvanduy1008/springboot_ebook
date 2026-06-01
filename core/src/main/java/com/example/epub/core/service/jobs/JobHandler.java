package com.example.epub.core.service.jobs;
import com.example.epub.core.model.jobs.JobModels.Lease;
public interface JobHandler {
    String type();
    /** Expensive preparation happens outside a DB transaction; the returned action persists under the lease lock. */
    Runnable prepare(Lease job) throws Exception;
    default void failed(Lease job) { }
    default void maintain() { }
    final class Rejected extends RuntimeException {
        private final String code;
        public Rejected(String code) {super(code);if(code==null||!code.matches("[A-Z][A-Z0-9_]{0,79}"))throw new IllegalArgumentException("Safe code required");this.code=code;}
        public String code() {return code;}
    }
}
