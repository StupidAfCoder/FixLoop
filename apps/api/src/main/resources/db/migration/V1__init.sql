 -- (PostgreSQL 16, hosted on Supabase)

--Reference data 
CREATE TABLE departments (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         text NOT NULL UNIQUE,
  code         text NOT NULL UNIQUE,
  kind         text NOT NULL CHECK (kind IN ('SERVICE', 'RESTRICTED')),
  head_user_id uuid,
  active       boolean NOT NULL DEFAULT true
);

CREATE TABLE locations (
  id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_id uuid REFERENCES locations(id),
  type      text NOT NULL CHECK (type IN ('CAMPUS', 'BLOCK', 'FLOOR', 'ROOM', 'AREA')),
  name      text NOT NULL,
  path      text NOT NULL,
  active    boolean NOT NULL DEFAULT true
);
CREATE INDEX locations_path_idx ON locations (path text_pattern_ops);

CREATE TABLE users (
  id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email                text NOT NULL UNIQUE CHECK (email = lower(email)),
  password_hash        text NOT NULL,
  full_name            text NOT NULL,
  role                 text NOT NULL CHECK (role IN ('STUDENT', 'STAFF', 'SUPERVISOR', 'ADMIN')),
  department_id        uuid REFERENCES departments(id),
  scope_location_id    uuid REFERENCES locations(id),
  email_verified       boolean NOT NULL DEFAULT false,
  must_change_password boolean NOT NULL DEFAULT false,
  active               boolean NOT NULL DEFAULT true,
  created_at           timestamptz NOT NULL DEFAULT now(),
  last_login_at        timestamptz,
  CONSTRAINT staff_needs_department CHECK (role <> 'STAFF' OR department_id IS NOT NULL)
);

ALTER TABLE departments
  ADD CONSTRAINT departments_head_fk FOREIGN KEY (head_user_id) REFERENCES users(id);

CREATE TABLE categories (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name             text NOT NULL UNIQUE,
  theme            text NOT NULL CHECK (theme IN ('INFRASTRUCTURE', 'HOSTEL', 'MESS', 'IT',
                                                  'TRANSPORT', 'SAFETY', 'ACADEMIC',
                                                  'ADMINISTRATIVE', 'SENSITIVE', 'OTHER')),
  default_severity text NOT NULL CHECK (default_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  photo_policy     text NOT NULL CHECK (photo_policy IN ('REQUIRED', 'OPTIONAL', 'DISABLED')),
  is_sensitive     boolean NOT NULL DEFAULT false,
  active           boolean NOT NULL DEFAULT true,
  sort_order       int NOT NULL DEFAULT 0
);

-- Configuration (behaviour is data)
CREATE TABLE status_transitions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  from_status   text CHECK (from_status IN ('REPORTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED',
                                            'VERIFIED', 'REOPENED', 'REJECTED')),
  to_status     text NOT NULL CHECK (to_status IN ('REPORTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED',
                                                   'VERIFIED', 'REOPENED', 'REJECTED')),
  allowed_roles text[] NOT NULL,
  requires_note boolean NOT NULL DEFAULT false,
  system_only   boolean NOT NULL DEFAULT false,
  CONSTRAINT uq_transition UNIQUE NULLS NOT DISTINCT (from_status, to_status)
);

CREATE TABLE routing_rules (
  id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  priority           int NOT NULL,
  category_id        uuid REFERENCES categories(id),
  location_scope_id  uuid REFERENCES locations(id),
  min_severity       text CHECK (min_severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  department_id     uuid NOT NULL REFERENCES departments(id),
  active             boolean NOT NULL DEFAULT true,
  note               text
);
CREATE INDEX routing_rules_active_priority_idx ON routing_rules (active, priority);

CREATE TABLE sla_policies (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  category_id      uuid REFERENCES categories(id),       -- NULL = default policy
  severity         text NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  response_minutes int NOT NULL CHECK (response_minutes > 0),
  resolve_minutes  int NOT NULL CHECK (resolve_minutes > 0),
  CONSTRAINT uq_sla UNIQUE NULLS NOT DISTINCT (category_id, severity)
);

CREATE TABLE settings (
  key   text PRIMARY KEY,
  value text NOT NULL
);

-- Complaints 
CREATE SEQUENCE complaint_code_seq;

CREATE TABLE complaints (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code             text NOT NULL UNIQUE,                  -- FL-2026-000123, built in app from complaint_code_seq
  reporter_id      uuid NOT NULL REFERENCES users(id),
  category_id      uuid NOT NULL REFERENCES categories(id),
  location_id      uuid NOT NULL REFERENCES locations(id),
  title            text NOT NULL CHECK (char_length(title) BETWEEN 5 AND 120),
  description      text CHECK (char_length(description) <= 2000),
  severity         text NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  status           text NOT NULL CHECK (status IN ('REPORTED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED',
                                                   'VERIFIED', 'REOPENED', 'REJECTED')),
  department_id    uuid REFERENCES departments(id),
  assigned_to      uuid REFERENCES users(id),
  sla_due_at       timestamptz,
  escalation_level int NOT NULL DEFAULT 0,
  reopen_count     int NOT NULL DEFAULT 0,
  version          int NOT NULL DEFAULT 0,
  idempotency_key  text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  resolved_at      timestamptz,
  verified_at      timestamptz,
  CONSTRAINT uq_complaint_idem UNIQUE (reporter_id, idempotency_key)
);

CREATE INDEX complaints_queue_idx        ON complaints (department_id, status, created_at DESC);
CREATE INDEX complaints_reporter_idx     ON complaints (reporter_id, created_at DESC);
CREATE INDEX complaints_sla_open_idx     ON complaints (status, sla_due_at)
  WHERE status NOT IN ('RESOLVED', 'VERIFIED', 'REJECTED');
CREATE INDEX complaints_similar_open_idx ON complaints (location_id, category_id)
  WHERE status NOT IN ('RESOLVED', 'VERIFIED', 'REJECTED');

CREATE TABLE complaint_events (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  complaint_id  uuid NOT NULL REFERENCES complaints(id),
  actor_id      uuid REFERENCES users(id),                  -- NULL for system
  actor_role    text NOT NULL CHECK (actor_role IN ('STUDENT', 'STAFF', 'SUPERVISOR', 'ADMIN', 'SYSTEM')),
  event_type    text NOT NULL CHECK (event_type IN ('CREATED', 'ROUTED', 'ROUTING_FALLBACK', 'REASSIGNED',
                                                    'CLAIMED', 'RELEASED', 'RESOLVED', 'VERIFIED',
                                                    'REOPENED', 'REJECTED', 'ESCALATED', 'NOTE')),
  from_status   text,
  to_status     text,
  payload       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX complaint_events_timeline_idx ON complaint_events (complaint_id, created_at);

CREATE FUNCTION forbid_event_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'complaint_events is append-only';
END;
$$;

CREATE TRIGGER complaint_events_immutable
  BEFORE UPDATE OR DELETE ON complaint_events
  FOR EACH ROW EXECUTE FUNCTION forbid_event_change();

CREATE TABLE attachments (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  complaint_id  uuid NOT NULL REFERENCES complaints(id),
  event_id      bigint REFERENCES complaint_events(id),     -- NULL for the original report
  kind          text NOT NULL CHECK (kind IN ('REPORT', 'RESOLUTION')),
  storage_key   text NOT NULL,
  mime_type     text NOT NULL,
  size_bytes    bigint NOT NULL CHECK (size_bytes > 0),
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- Messaging and auth
CREATE TABLE notifications_outbox (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES users(id),
  channel          text NOT NULL CHECK (channel IN ('IN_APP', 'EMAIL', 'PUSH')),
  type             text NOT NULL,
  payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
  status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
  attempts         int NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  created_at       timestamptz NOT NULL DEFAULT now(),
  sent_at          timestamptz,
  read_at          timestamptz
);
CREATE INDEX notifications_dispatch_idx ON notifications_outbox (status, next_attempt_at);
CREATE INDEX notifications_inbox_idx    ON notifications_outbox (user_id, created_at DESC);

CREATE TABLE refresh_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id),
  token_hash  text NOT NULL UNIQUE,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  replaced_by uuid REFERENCES refresh_tokens(id)
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- Analytics as views
CREATE VIEW v_open_by_status AS
  SELECT status, count(*) AS total
  FROM complaints
  GROUP BY status;

CREATE VIEW v_open_by_department AS
  SELECT d.name AS department, count(*) AS total
  FROM complaints c
  JOIN departments d ON d.id = c.department_id
  WHERE c.status NOT IN ('VERIFIED', 'REJECTED')
  GROUP BY d.name;

CREATE VIEW v_created_per_day AS
  SELECT date_trunc('day', created_at)::date AS day, count(*) AS total
  FROM complaints
  WHERE created_at >= now() - interval '30 days'
  GROUP BY 1;

-- Seed: the workflow transitions 
INSERT INTO status_transitions (from_status, to_status, allowed_roles, requires_note, system_only) VALUES
  (NULL,          'REPORTED',    '{STUDENT,ADMIN}',               false, false),
  ('REPORTED',    'ASSIGNED',    '{SYSTEM,SUPERVISOR,ADMIN}',     false, false),
  ('REPORTED',    'REJECTED',    '{SUPERVISOR,ADMIN}',            true,  false),
  ('ASSIGNED',    'ASSIGNED',    '{SUPERVISOR,ADMIN}',            true,  false),
  ('ASSIGNED',    'IN_PROGRESS', '{STAFF,SUPERVISOR}',            false, false),
  ('IN_PROGRESS', 'ASSIGNED',    '{STAFF,SUPERVISOR}',            true,  false),
  ('IN_PROGRESS', 'RESOLVED',    '{STAFF,SUPERVISOR}',            true,  false),
  ('RESOLVED',    'VERIFIED',    '{STUDENT}',                     false, false),
  ('RESOLVED',    'REOPENED',    '{STUDENT}',                     true,  false),
  ('REOPENED',    'ASSIGNED',    '{SYSTEM,SUPERVISOR}',           false, true);