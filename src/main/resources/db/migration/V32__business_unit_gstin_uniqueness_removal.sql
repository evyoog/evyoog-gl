-- GSTIN uniqueness is enforced externally by the Indian GST system.
-- eVyoog stores it but does not police it — two Business Units under the
-- SAME Legal Entity may legitimately share a GSTIN (e.g. multiple factory
-- units registered under one state GSTIN). Service-level uniqueness is now
-- scoped to "not within the same Legal Entity" (see BusinessUnitService),
-- so the blanket DB-level unique constraint must go too.
ALTER TABLE gl.business_unit DROP CONSTRAINT IF EXISTS business_unit_gstin_key;
