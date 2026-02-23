# Touch Lock — Documentation Index

Engineering documentation for the Touch Lock Android app.

---

## Documents

| File | Purpose |
|------|---------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Start here.** System design, data flow, component responsibilities, known constraints, and tech debt. |
| [DEBUGGING_GUIDE.md](DEBUGGING_GUIDE.md) | Troubleshooting common issues, logcat filters, testing scenarios, debugging checklist. |
| [TESTING_GUIDE.md](TESTING_GUIDE.md) | Testing strategy, tier breakdown (unit/integration/E2E), examples, anti-patterns. |
| [learnings.md](learnings.md) | Why-not-X design decisions, Android patterns used with code examples, key takeaways. |

---

## When to Update Docs

| Change type | Update |
|-------------|--------|
| New/removed permission | `ARCHITECTURE.md` (System Integrations) + `README.md` |
| New Intent action on service | `ARCHITECTURE.md` (Intent API table) |
| New overlay type | `ARCHITECTURE.md` (Overlay System table) |
| New DataStore key | `ARCHITECTURE.md` (Persistence table) |
| Architecture layer added/removed | `ARCHITECTURE.md` + `README.md` |
| New major user flow | `ARCHITECTURE.md` (Core Flows) |
| New known issue | `ARCHITECTURE.md` (Risks/Constraints) + `DEBUGGING_GUIDE.md` |
| Small local change (single method) | No update needed |
