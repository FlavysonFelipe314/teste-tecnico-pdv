ALTER TABLE documents ADD COLUMN payload TEXT;
ALTER TABLE documents ADD COLUMN cycle_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE documents ADD COLUMN next_attempt_at TIMESTAMP NULL;
ALTER TABLE documents ADD COLUMN lease_until TIMESTAMP NULL;
ALTER TABLE documents ADD COLUMN lease_token VARCHAR(36);
CREATE INDEX documents_work ON documents(status,next_attempt_at,lease_until);

-- Previously interrupted/failed documents become recoverable without reconfirming stock.
UPDATE documents SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP,reason=NULL
WHERE status IN ('FAILED','PROCESSING');
INSERT INTO documents(request_id,document_key,status,next_attempt_at)
SELECT r.id,CONCAT('doc-',r.id),'PENDING',CURRENT_TIMESTAMP
FROM replenishment_requests r LEFT JOIN documents d ON d.request_id=r.id
WHERE r.status='CONFIRMED' AND d.request_id IS NULL;

CREATE TABLE inventory_versions (
  location_id VARCHAR(40) PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY(location_id) REFERENCES locations(id)
);
INSERT INTO inventory_versions(location_id) SELECT id FROM locations;
