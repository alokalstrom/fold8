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

The merged debug manifest retains an exported Home alias and a private PreviewActivity. Both cold and warm public entries sanitize launch extras in source. The phone is currently disconnected, so physical launch checks of 0.18.1 remain pending; the previously user-tested installation is 0.18.0. The correction does not alter animation or display policy.
