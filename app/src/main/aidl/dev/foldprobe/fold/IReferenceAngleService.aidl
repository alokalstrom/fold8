package dev.foldprobe.fold;

// Fixed angle read and bounded early-display trial; no arbitrary paths or state IDs.
interface IReferenceAngleService {
    String readAngle() = 0;
    int getServiceUid() = 1;
    void armEarlyDisplay() = 2;
    String getEarlyDisplayStatus() = 3;
    void cancelEarlyDisplay() = 4;
    void armOuterFirstDisplay() = 5;
    void armAutomaticDisplay() = 6;
    void armSteadyDisplayTrial() = 7;
    void renewForegroundLease() = 8;
    void destroy() = 16777114;
}
