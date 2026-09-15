# Validation

## Installed prototype: 0.18.0-sweep

- Debug build succeeded.
- 58 unit tests passed; lint reported 0 errors and 40 warnings.
- Device-side runtime shader initialization succeeded.
- App slots and saved Fold Study preferences were preserved through the upgrade.
- The user confirmed the requested slow opening test worked, including the sweep check for a dark boundary or jumps.
- A closed-cover screenshot was inspected and was sharp. The bounded capture attempt ended before the user started opening, so no controlled intermediate sweep image pairs were obtained.

Earlier physical 0.17.0 checks established the retained layout and opposite cover/inner blur treatments at a held partial angle, sharp calendar content fully open, and return to the unlocked cover after closing. Those screenshots are local evidence and are not included in this public repository.

## Review status

Independent Claude CLI review results are tracked in [issue #1](https://github.com/alokalstrom/fold8/issues/1) and its linked pull request. The earlier provisional source-only run was interrupted without a result and is not counted as a completed review. Passing automated checks is not a substitute for that review.

## Remaining checks

- Controlled intermediate captures of the spatial sweep, and performance comparison against the previous uniform effect.
- Physical fault-cleanup behavior for the foreground lease.
- The known inner app-launch display-remapping blink remains accepted for this prototype.

User approval of the visible animation does not establish exact iOS behavior, portability to other devices, or production readiness.

## Initial Claude review findings

The first complete-diff review found an external-entry boundary issue. Source 0.18.1 makes PreviewActivity private and strips extras from the public Home alias on both creation and re-entry; making only the Activity private would not close the alias path.

Two hypotheses were not applied: [ROLE_HOME is documented since API 29](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_HOME) (so it predates minSdk 30), and the Gradle wrapper JAR was already staged. The first text-only review payload omitted the binary wrapper entry; subsequent reviews include its tracked metadata. Non-constant-time local token comparison was explicitly noted as non-blocking. USB parser/auth test expansion remains a follow-up, not a claim of additional existing coverage. The corrected source is re-reviewed as part of the same issue before publication.

## 0.18.1 entry correction checks

The merged debug manifest retains an exported Home alias and a private PreviewActivity. Both cold and warm public entries sanitize launch extras in source. Version 0.18.1 was subsequently installed: cold and warm Home entry succeeded, and the user confirmed the requested opening, closing, cover touch blocking, app launch and return Home cycle. Hostile-extra checks have not been physically verified. The correction does not alter animation or display policy.

## 0.19.0 perspective experiment

Issue #3 tracks an optional inverse projection applied to cover sampling coordinates in both complementary sharp/blur branches. It uses the same measured progress as the sweep. The hinge-side edge is fixed, horizontal sampling is compressed and vertical scale varies across the surface; all source samples stay inside the cover scene. Identity is retained at 0–5° and 165–180°, and on the entire inner scene. The bounded curve avoids an inverse-cosine singularity at 90°. These angles and strengths are experimental choices, not measured Apple parameters.

The control starts disabled and persists its value. The pre-existing effect remains the fallback below Android 13 or when runtime shader construction fails. Numerical checks cover no holes/foldover, hinge alignment, resting identity and pause/reversal continuity. They do not establish physical perceptual alignment, GPU sampling quality or performance. Device comparison remains required before choosing a new default. The existing panel control and app navigation policy are unchanged. Review/build/device results are recorded in issue #3 and its linked PR.
