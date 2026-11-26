# Contributing

Work on `dev`. Review `git diff` and stage the files you mean to commit.
Write a short commit message. Keep logs, editor files, and course PDFs local.

After checking your change, merge and push it from `main`.

```sh
git switch main &&
git pull --ff-only origin main &&
git merge dev &&
git push origin main &&
git switch dev
```
