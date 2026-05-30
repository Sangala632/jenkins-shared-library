# jenkins-shared-library

Overview
Library of shared Jenkins pipeline steps, helpers, and global vars used across pipelines.

Why this exists
To centralize CI/CD logic and avoid duplication across Jenkinsfiles.

Workflows
- Update shared steps in vars/ or src/
- Update pipeline to call library functions

Actions (quick start)
1. Publish library to Jenkins (via Global Pipeline Libraries config).
2. Reference the library in Jenkinsfiles: @Library('shared-lib') _
3. Test pipeline runs and update as needed.

Key files
- vars/, src/ (if present)

Notes
- Version the library tags for stable pipeline behavior.
