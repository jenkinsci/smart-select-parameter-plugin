# Changelog

## 1.24.v11a_363729346 — 2026-11-11

- Align the dropdown selection indicators with Jenkins native checkbox and radio styling (size, checked state and theme colors) while keeping selection, search and keyboard behavior unchanged.

## 1.23.v4b_54a_84da_526 — 2026-11-11

- First official Jenkins update-center release (requires Jenkins 2.555.3 or newer).
- Includes the reviewed feature set: searchable single/multi-select parameters with separate labels and values, administrator-approved REST sources, server-validated cascading choices, bounded option pages, keyboard navigation and an English-default UI with Simplified Chinese translations.

## 1.0.2-SNAPSHOT — publication preparation (unreleased)

- Default the complete UI to English and provide Simplified Chinese resource bundles for job/system configuration, selection controls, dynamic status messages and server validation/errors.
- Add English documentation, a Chinese guide, contribution/security policies, CI and hosting preparation material.
- Define explicit source/issue/CI metadata for a proposed repository; public creation and hosting remain pending.
- Provide local source-distribution checks and exclude controller records, generated binaries and private files.
- Preserve parameter behavior from the compatibility snapshot while raising the public-release baseline to Jenkins 2.555.3, as required for new hosting requests.
- Use the required current parent POM, synchronized BOM and strict build/import checks; migrate maintained tests to JUnit Jupiter.
- Add official Jenkins security scanning, future hosting-team CODEOWNERS and Java 25 CI coverage; use a theme-aware popup shadow.
- Enforce formatting rather than skipping it. Existing deployed local compatibility builds are not changed.

- Protect choices validation with POST/CSRF enforcement and job configuration permission (administrator permission outside a job context).

## Local milestones

1.0.1 targets Jenkins 2.547 and a matching test BOM. 1.0.0 introduced validated cascades, bounded list pages, keyboard and retry handling. 0.2.0 added label/value choices and REST sources. 0.1.0 provided the base searchable single/multiple selection.

These are local development milestones, not official update-center releases.
