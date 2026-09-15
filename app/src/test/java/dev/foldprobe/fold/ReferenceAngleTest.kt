package dev.foldprobe.fold

import org.junit.Assert.*
import org.junit.Test

class ReferenceAngleTest {
    @Test fun acceptsMeasuredRecordsIncludingSecondarySentinels() {
        assertEquals(52f, ReferenceAngle.parse("52,3,-1,-1,52,3,0,0,0"))
        assertEquals(180f, ReferenceAngle.parse("180,3,-1,-1,180,3,0,0,0\n"))
        assertEquals(0f, ReferenceAngle.parse("0,1,-1,-1,0,1,0,0,0"))
    }
    @Test fun rejectsFailuresAndMalformedRecordsInsteadOfInventingClosed() {
        listOf("", "-1", "Permission denied", "90,3", "NaN,3,-1,-1,90,3,0,0,0",
            "-1,3,-1,-1,0,3,0,0,0", "181,3,-1,-1,0,3,0,0,0",
            "90,3,-1,-1,90,3,0,0,bad").forEach { assertNull(it, ReferenceAngle.parse(it)) }
    }
}
