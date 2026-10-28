# Operations notes (foundation)

The current stack is for local development, not deployment. Follow the root
README to create `.env`, start Compose, check both health endpoints and stop the
stack. Local volumes persist across `docker compose down`; `--volumes` deletes
them and must only be used on disposable data.

Before any hosted deployment: rotate the previously exposed database password,
review security and access controls, provision secrets outside Git, configure
HTTPS and restricted CORS, validate backups and obtain explicit approval for a
migration plan. The state of the existing Supabase database has not been
inspected, so do not enable automatic baselining or point the API at it yet.
