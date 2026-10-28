# AI usage log

| Date (UTC) | Who / tool | Assistance | Human review and follow-up |
|---|---|---|---|
| 2026-10-28 | Arena.ai coding agent, at the repository owner's request | Reviewed the supplied v0.2 project bible and existing repository; drafted the local Docker setup, Spring Boot foundation, security defaults, tests, Flutter placeholder and documentation. Preserved the existing SQL migration; removed the tracked config containing an apparent database credential. | Team review and teach-back are still required. Rotate the exposed credential with the project owner; removing it from the current tree does not remove it from Git history. No Supabase connection was made. |

Do not merge AI-generated changes unread. Record subsequent human changes and
verification here in accordance with the project bible.
