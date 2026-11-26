# Contributing

Use English for project docs and keep changes focused. Open an issue to discuss
changes that affect the protocol or the demo.

Work on `dev` and review `git diff` before committing. Add only the files you
changed. Use a short commit message that describes the change. Keep local logs,
editor files, and course PDFs out of commits.

When a change is ready, use these commands to update `main`, merge `dev`, and
push `main`.

```sh
git switch main &&
git pull --ff-only origin main &&
git merge dev &&
git push origin main &&
git switch dev
```

Stop and resolve any error before continuing. Once tests exist, run the relevant
cases in the [test and demo plan](docs/testing.md) before merging.
