package com.evyoog.gl.aie.service;

import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.dto.PostingResult;
import com.evyoog.gl.posting.service.PostingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a single journal in its own transaction, isolated from the enclosing
 * {@link AiePipelineService#ingest(com.evyoog.gl.aie.dto.AieImportRequest, String, String)}
 * transaction.
 *
 * {@link PostingEngine#post(PostingRequest)} is itself {@code @Transactional}.
 * Called directly from within another {@code @Transactional} method it joins
 * the SAME physical transaction (propagation REQUIRED) — so when it throws
 * (e.g. BALANCING_SEGMENT_CROSSED, ACCOUNT_NOT_POSTABLE), Spring marks that
 * *shared* transaction rollback-only the moment the exception exits post()'s
 * proxy, even though AiePipelineService catches it afterward and returns a
 * normal FAILED-batch response. The batch record then fails to commit with
 * UnexpectedRollbackException ("marked as rollback-only"), hiding the real
 * cause. REQUIRES_NEW here suspends the pipeline's transaction and runs the
 * posting attempt in its own, so a posting failure only rolls back the
 * posting attempt — never the batch bookkeeping around it. Same pattern as
 * {@code CoaImportRowService.createAccountIsolated()} (GL-06).
 */
@Service
@RequiredArgsConstructor
public class PostingIsolationService {

    private final PostingEngine postingEngine;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PostingResult postIsolated(PostingRequest request) {
        return postingEngine.post(request);
    }
}
