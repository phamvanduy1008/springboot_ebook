package com.example.epub.core.service.jobs;
import com.example.epub.core.model.jobs.JobModels.Lease;
public interface JobHandler {
    String type();
    /** Expensive preparation happens outside a DB transaction; the returned action persists under the lease lock. */
    Runnable prepare(Lease job) throws Exception;
}
