package com.warden.controlledsandbox.framework.core;

import com.warden.controlledsandbox.contract.VirtualCameraProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualCameraSourceSnapshot;
import com.warden.controlledsandbox.contract.VirtualCellInfoSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationPointSnapshot;
import com.warden.controlledsandbox.contract.VirtualLocationProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonyProfileSnapshot;
import com.warden.controlledsandbox.contract.VirtualTelephonySlotSnapshot;
import java.util.List;

/** Contract-level fixture for trajectory sampling, media isolation metadata and cell profiles. */
public final class VirtualProfileContractSelfTest {
    public static void main(String[] args) throws Exception {
        trajectoryIsInterpolated();
        cameraSourceIsBoundedAndHashed();
        cellProfileIsRetained();
        require("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                        .equals(VirtualCameraCaptureEngine.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "camera hash helper");
        System.out.println("PASS VirtualProfileContractSelfTest");
    }

    private static void trajectoryIsInterpolated() {
        VirtualLocationProfileSnapshot profile = new VirtualLocationProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, "gps", true,
                31d, 121d, 4d, 5f, 2f, 90f, 1_000L, 1_000_000_000L,
                500L, true, 8, 4, "NMEA",
                VirtualLocationProfileSnapshot.TRAJECTORY_ROUTE, 500L,
                List.of(new VirtualLocationPointSnapshot(0L, 31d, 121d, 4d, 5f, 2f, 90f),
                        new VirtualLocationPointSnapshot(1_000L, 32d, 122d, 6d, 7f, 4f, 100f)),
                VirtualLocationProfileSnapshot.TIME_POLICY_PROFILE,
                VirtualLocationProfileSnapshot.ELAPSED_POLICY_PROFILE);
        VirtualLocationPointSnapshot point = profile.pointAt(1_500_000_000L);
        require(Math.abs(point.latitude() - 31.5d) < 0.0001d, "trajectory latitude");
        require(Math.abs(point.longitude() - 121.5d) < 0.0001d, "trajectory longitude");
        VirtualLocationProfileSnapshot sample = profile.sampleAt(9_000L, 1_500_000_000L);
        require(sample.timeMs() == 1_500L, "profile timestamp policy");
        require(sample.elapsedRealtimeNanos() == 2_000_000_000L, "profile elapsed policy");
    }

    private static void cameraSourceIsBoundedAndHashed() {
        VirtualCameraSourceSnapshot source = new VirtualCameraSourceSnapshot(
                VirtualCameraSourceSnapshot.IMAGE, "virtual-camera/source.jpg", "image/jpeg",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                1280, 720, 90, 0L);
        VirtualCameraProfileSnapshot profile = new VirtualCameraProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, true, true, false, 1,
                List.of("0"), List.of("0"), List.of(), source, true);
        require(profile.source().isConfigured() && profile.substituteCaptureResult(),
                "camera substitution profile");
        try {
            new VirtualCameraSourceSnapshot(VirtualCameraSourceSnapshot.IMAGE,
                    "C:\\private\\host.jpg", "image/jpeg",
                    source.sha256(), 1, 1, 0, 0L);
            throw new AssertionError("host path accepted");
        } catch (IllegalArgumentException expected) { }
    }

    private static void cellProfileIsRetained() {
        VirtualTelephonySlotSnapshot slot = new VirtualTelephonySlotSnapshot(
                0, 1001, "000000000000000", "00000000000000", "001010000000000",
                "8901010000000000000", "", "00101", "00101", "us", "us", "CS", 1,
                5, 0, 0, false, false);
        VirtualCellInfoSnapshot cell = new VirtualCellInfoSnapshot(
                VirtualCellInfoSnapshot.LTE, 1, 1, 10, 20, 30L, 4, 100,
                true, -70);
        VirtualTelephonyProfileSnapshot profile = new VirtualTelephonyProfileSnapshot(
                VirtualLocationProfileSnapshot.MODE_STATIC, 1001, 1001, true, true, false,
                List.of(slot), List.of(cell));
        require(profile.cells().size() == 1 && profile.cells().get(0).cid() == 30L,
                "cell profile");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
