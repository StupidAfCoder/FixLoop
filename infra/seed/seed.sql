-- Optional, synthetic LOCAL reference data only. Never use this as real campus data.
-- Insert-only and repeatable on a fresh local database; no users or complaints.
BEGIN;

INSERT INTO departments (id, name, code, kind) VALUES
  ('10000000-0000-4000-8000-000000000001', 'Demo Electrical', 'DEMO_ELECTRICAL', 'SERVICE'),
  ('10000000-0000-4000-8000-000000000002', 'Demo Grievance Committee', 'DEMO_GRIEVANCE', 'RESTRICTED'),
  ('10000000-0000-4000-8000-000000000003', 'Demo Admin Triage', 'DEMO_TRIAGE', 'RESTRICTED')
ON CONFLICT DO NOTHING;

INSERT INTO locations (id, parent_id, type, name, path) VALUES
  ('20000000-0000-4000-8000-000000000001', NULL, 'CAMPUS', 'Demo Campus', '/demo-campus/'),
  ('20000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000001',
   'BLOCK', 'Demo Block', '/demo-campus/demo-block/')
ON CONFLICT DO NOTHING;

INSERT INTO categories (id, name, theme, default_severity, photo_policy, is_sensitive, sort_order) VALUES
  ('30000000-0000-4000-8000-000000000001', 'Demo - broken light', 'INFRASTRUCTURE', 'MEDIUM', 'OPTIONAL', false, 10),
  ('30000000-0000-4000-8000-000000000002', 'Demo - sensitive concern', 'SENSITIVE', 'HIGH', 'DISABLED', true, 20)
ON CONFLICT DO NOTHING;

INSERT INTO routing_rules (id, priority, category_id, department_id, note) VALUES
  ('40000000-0000-4000-8000-000000000001', 100, '30000000-0000-4000-8000-000000000001',
   '10000000-0000-4000-8000-000000000001', 'Synthetic example'),
  ('40000000-0000-4000-8000-000000000002', 100, '30000000-0000-4000-8000-000000000002',
   '10000000-0000-4000-8000-000000000002', 'Sensitive example: restricted department only'),
  ('40000000-0000-4000-8000-000000000003', 9999, NULL,
   '10000000-0000-4000-8000-000000000003', 'Synthetic fallback to restricted admin triage')
ON CONFLICT DO NOTHING;

INSERT INTO sla_policies (id, category_id, severity, response_minutes, resolve_minutes) VALUES
  ('50000000-0000-4000-8000-000000000001', NULL, 'LOW', 1440, 10080),
  ('50000000-0000-4000-8000-000000000002', NULL, 'MEDIUM', 480, 4320),
  ('50000000-0000-4000-8000-000000000003', NULL, 'HIGH', 120, 1440),
  ('50000000-0000-4000-8000-000000000004', NULL, 'CRITICAL', 30, 240)
ON CONFLICT DO NOTHING;

COMMIT;
