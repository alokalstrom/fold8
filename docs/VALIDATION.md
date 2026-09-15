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

## 0.20.0 shared-plane experiment

The user saw perspective movement in 0.19.0 but rejected it as a match. The replacement derives horizontal movement and vertical compensation from the same 3D construction: eye at `(W/2,H/2,400)`, rotated cover point at `(u W cos(a),v H,u W sin(a))`, ray intersection with the fixed image plane `z=0`. In normalized cover coordinates, `k=W sin(a)/400`, source `x=u(cos(a)-k/2)/(1-ku)` and `y=1/2+(v-1/2)/(1-ku)`. Unlike 0.19.0, this contracts the drawn content vertically toward the approaching edge, opposing the panel's physical perspective enlargement.

Numerical tests compare sampling against an independent 3D ray/plane intersection at 30°, 45° and 60°, including the sign of the vertical compensation. The measured angle is used exactly from 10–65°; the transform is identity through 5°, eased in up to 10°, smoothly capped at 75° over measured 65–85°, and blended back to identity from 90–165°. These bounds avoid foldover and preserve resting touch alignment. They are deliberate approximations outside the comparison angles, not a physical model of the entire fold.

Rays may land above/below or slightly beside the finite source image. Both branches clamp to its half-pixel inset and repeat edge pixels instead of creating transparent bands; this can smear edge content and needs visual verification. The assumed eye position, panel dimensions, image-plane alignment, display geometry and absence of eye tracking limit perceptual accuracy. Blur parameters, inner content, app navigation and display/touch policy are unchanged. The existing comparison switch is reused and its persisted value is retained. Physical 0.20.0 comparison remains pending; issue #3 and PR #2 record review/build/device results.

## 0.21.0 bottom anchor and black upper wedge

The user tested 0.20.0 and identified two mismatches: no black area above the tilted image, and lower rows sloping upward while upper rows slope downward. The vertical eye position is now `H`, not `H/2`, so source `y=1+(v-1)/(1-ku)`. Thus the drawn location of any source row `r<1` is `v=r+(1-r)ku`: both upper and lower rows slope downward toward the approaching right edge. The bottom edge remains fixed. This is a reference-fit viewpoint assumption, not a measured eye location.

Source rays above `y=0` now produce opaque black, with a one-source-pixel smoothstep boundary. The resulting upper wedge has normalized height `ku`, and collapses at the hinge and at identity. Sharp and soft branches share the same coverage and complementary weights, so the wedge remains opaque without an additional dark seam. Side/bottom sampling retains edge clamping. The CPU and shader use the same vertical-anchor constant through a uniform.

The independent ray/plane test now uses the bottom-aligned eye. A regression test numerically inverts the sampler for upper AND lower rows and checks their downward slopes, plus the black-wedge boundary and fixed bottom. These are geometry checks, not proof of GPU antialiasing quality or perceptual agreement. Physical 0.21.0 comparison remains pending. Horizontal projection, blur settings, resting identity and display/navigation/touch policies are unchanged.
