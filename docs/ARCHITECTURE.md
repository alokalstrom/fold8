# Architecture

The Kotlin/Compose application has two entry points: Fold Probe for diagnostics and Fold Study for the home scene. Both belong to package `dev.foldprobe`.

## Angle and display control

`DiagnosticAngleSource` prefers a direct read when permitted and otherwise uses `ShizukuAngleClient`. The privileged reader parses the Samsung `sub_accelerometer_sensor/read_angle_data` diagnostic getter. Standard sensor observations remain separate from this measured-angle path. The optional USB bridge binds only to loopback and authenticates its connection with an ephemeral token.

`VisualProgressController` maps measured degrees to progress and interpolates toward a received sample over 80 ms without forecasting. Stale data pauses tracking. The user can stop or reverse a physical movement and the scene follows the same progress path.

The Shizuku display controller requests Samsung's cover-primary concurrent display state during overlap. A renewable foreground lease bounds the request if Home stops responding. The code preserves cancellation, lock/lifecycle handling and input guards. Undocumented state identifiers are specific to the tested device; they must not be assumed valid for other phones. Historical shell experiments are not part of this source import.

## Shared scene and sweep

`DuoLayout` provides approximate shared physical coordinates for the two panel crops. The 16 app slots retain a four-by-four arrangement on the right. Clock and horizontal dock keep their cover positions. The left panel adds the current local date and month calendar.

`RevealSweep` produces smooth spatial masks from the current progress. `RevealSweepShader` combines two branches of one rendered scene: a sharp branch and a native Gaussian-blurred branch. Their complementary premultiplied weights are added, preserving opacity through the mask boundary. The cover softens from right to left; the incoming left inner surface resolves from the hinge toward the outer edge. The right inner app group stays sharp. This is a sharp/blur mixture, not a variable Gaussian kernel or a measured iOS shader.

The closed cover and fully open inner scene bypass the effect. API levels below 33 or runtime-shader initialization failures retain the older uniform blur treatment. The historical optional warp shader is independent of the normal sweep.

## Data

The launcher queries launchable personal-profile apps and stores component choices in app-private preferences. Diagnostics stay in the app until the user explicitly exports them. No remote analytics backend is configured. Build output, local review output and device captures are excluded from version control.

## External entry boundary

The renderer Activity is not exported. The exported Home/launcher alias still opens the home normally, but its launch extras are stripped on initial entry and subsequent intents. Debug controls (including a USB bridge token and display-test switches) are accepted only through an explicit entry to the private Activity, whose caller access is enforced by Android. MainActivity does not forward external launch extras.
