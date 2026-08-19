#!/usr/bin/env python3
"""Regenerate the bounded Host Activity stub family and the scale fixture.

Physical Host Activity count is a process-slot x window-family x activity-window constant:
64 x 2 x 16 = 2048.  It must not grow with Guest Activity declaration count.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ORDINARY_SLOTS = 64
WINDOW_SLOTS = 16
ACTIVITY_PKG = "com.warden.controlledsandbox.runtime.component.activity"
STUB_DIR = ROOT / "sandbox-runtime/src/main/java/com/warden/controlledsandbox/runtime/component/activity"
MANIFEST = ROOT / "sandbox-runtime/src/main/AndroidManifest.xml"
FIXTURE_ROOT = ROOT / "fixture-activity-scale"
SCALE_COUNT = 128


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def generate_translucent_stubs() -> None:
    for slot in range(ORDINARY_SLOTS):
        write(
            STUB_DIR / f"StubActivityTranslucent{slot}.java",
            "package " + ACTIVITY_PKG + ";\n"
            "public final class StubActivityTranslucent"
            + str(slot)
            + " extends StubActivityBase { }\n",
        )


def generate_window_stubs() -> None:
    """Activity-window multiplexing (window 1..15) so ActivityStarter can match the exact record.

    Window 0 reuses the existing StubActivity{slot} / StubActivityTranslucent{slot} classes.
    Each additional window is a distinct physical ComponentName so reorder/clear-top/single-top
    reuse of a non-top sibling does not collide with the shared slot stub.
    """
    for window in range(1, WINDOW_SLOTS):
        for slot in range(ORDINARY_SLOTS):
            write(
                STUB_DIR / f"StubActivity{slot}W{window}.java",
                "package " + ACTIVITY_PKG + ";\n"
                "public final class StubActivity" + str(slot) + "W" + str(window)
                + " extends StubActivityBase { }\n",
            )
            write(
                STUB_DIR / f"StubActivityTranslucent{slot}W{window}.java",
                "package " + ACTIVITY_PKG + ";\n"
                "public final class StubActivityTranslucent" + str(slot) + "W" + str(window)
                + " extends StubActivityBase { }\n",
            )


def _window_manifest_lines() -> list[str]:
    opaque = []
    translucent = []
    for window in range(1, WINDOW_SLOTS):
        for slot in range(ORDINARY_SLOTS):
            opaque.append(
                f'        <activity android:name="{ACTIVITY_PKG}.StubActivity{slot}W{window}" '
                f'android:exported="false" android:process=":guest{slot}" '
                f'android:launchMode="standard" android:theme="@style/ControlledSandbox.Stub" />'
            )
            translucent.append(
                f'        <activity android:name="{ACTIVITY_PKG}.StubActivityTranslucent{slot}W{window}" '
                f'android:exported="false" android:process=":guest{slot}" '
                f'android:launchMode="standard" android:theme="@style/ControlledSandbox.Stub.Translucent" />'
            )
    return opaque + translucent


def delete_index_coupled_stubs() -> None:
    for path in STUB_DIR.glob("StubActivitySlotVariants*.java"):
        path.unlink()
    variants = STUB_DIR / "StubActivityVariants.java"
    if variants.is_file():
        variants.unlink()


def rewrite_runtime_manifest() -> None:
    text = MANIFEST.read_text(encoding="utf-8")
    text = re.sub(r"\n[ \t]*<activity-alias\b[^>]*/>", "", text)
    text = re.sub(
        r"\n[ \t]*<activity\b[^>]*android:name=\""
        + re.escape(ACTIVITY_PKG)
        + r"\.[^\"]+\"[^>]*/>",
        "",
        text,
    )
    lines = [
        f'        <activity android:name="{ACTIVITY_PKG}.StubActivity{slot}" '
        f'android:exported="false" android:process=":guest{slot}" '
        f'android:launchMode="standard" android:theme="@style/ControlledSandbox.Stub" />'
        for slot in range(ORDINARY_SLOTS)
    ]
    lines.extend(
        f'        <activity android:name="{ACTIVITY_PKG}.StubActivityTranslucent{slot}" '
        f'android:exported="false" android:process=":guest{slot}" '
        f'android:launchMode="standard" android:theme="@style/ControlledSandbox.Stub.Translucent" />'
        for slot in range(ORDINARY_SLOTS)
    )
    lines.extend(_window_manifest_lines())
    insertion = "\n" + "\n".join(lines)
    if "</application>" not in text:
        raise SystemExit("MANIFEST_APPLICATION_CLOSE_MISSING")
    text = text.replace("</application>", insertion + "\n    </application>", 1)
    text = re.sub(r"\n{3,}", "\n\n", text)
    MANIFEST.write_text(text, encoding="utf-8", newline="\n")


def generate_scale_fixture() -> None:
    java_dir = FIXTURE_ROOT / "src/main/java/com/warden/controlledsandbox/fixture/scale"
    write(
        FIXTURE_ROOT / "build.gradle",
        """plugins { id 'com.android.application' }

apply from: rootProject.file('gradle/release-signing.gradle')

android {
    namespace = 'com.warden.controlledsandbox.fixture.scale'
    compileSdk = rootProject.ext.controlledCompileSdk
    buildToolsVersion rootProject.ext.controlledBuildTools

    defaultConfig {
        applicationId 'com.warden.controlledsandbox.fixture.scale'
        minSdk = rootProject.ext.controlledMinSdk
        targetSdk = rootProject.ext.controlledTargetSdk
        versionCode 1
        versionName '1.0-activity-scale'
    }

    buildTypes { release { minifyEnabled false } }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
""",
    )
    write(
        FIXTURE_ROOT / "gradle.lockfile",
        """# This is a Gradle generated file for dependency locking.
# Manual edits can break the build and are not advised.
# This file is expected to be part of source control.
empty=androidApis,androidJdkImage,androidTestUtil,coreLibraryDesugaring,debugAndroidTestAnnotationProcessorClasspath,debugAndroidTestCompileClasspath,debugAndroidTestRuntimeClasspath,debugAnnotationProcessorClasspath,debugCompileClasspath,debugReverseMetadataValues,debugRuntimeClasspath,debugUnitTestAnnotationProcessorClasspath,debugUnitTestCompileClasspath,debugUnitTestRuntimeClasspath,debugWearBundling,lintChecks,lintPublish,releaseAnnotationProcessorClasspath,releaseCompileClasspath,releaseReverseMetadataValues,releaseRuntimeClasspath,releaseUnitTestAnnotationProcessorClasspath,releaseUnitTestCompileClasspath,releaseUnitTestRuntimeClasspath,releaseWearBundling
""",
    )
    write(
        java_dir / "ScaleActivityBase.java",
        """package com.warden.controlledsandbox.fixture.scale;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public abstract class ScaleActivityBase extends Activity {
    public static final String ACTION_RETURN_RESULT =
            "com.warden.controlledsandbox.fixture.scale.RETURN_RESULT";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText(getClass().getName());
        view.setPadding(32, 32, 32, 32);
        setContentView(view);
        if (ACTION_RETURN_RESULT.equals(getIntent() == null ? "" : getIntent().getAction())) {
            Intent result = new Intent();
            result.putExtra("scaleActivity", getClass().getName());
            setResult(RESULT_OK, result);
            finish();
        }
    }
}
""",
    )
    write(
        java_dir / "ScaleResultCallerActivity.java",
        """package com.warden.controlledsandbox.fixture.scale;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public final class ScaleResultCallerActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText(getClass().getName());
        setContentView(view);
        if (state == null) {
            Intent request = new Intent(this, ScaleActivity127.class);
            request.setAction(ScaleActivityBase.ACTION_RETURN_RESULT);
            startActivityForResult(request, 127);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        TextView view = new TextView(this);
        view.setText("result=" + resultCode + " code=" + requestCode
                + " from=" + (data == null ? "" : data.getStringExtra("scaleActivity")));
        setContentView(view);
    }
}
""",
    )
    for index in range(SCALE_COUNT):
        write(
            java_dir / f"ScaleActivity{index:03d}.java",
            "package com.warden.controlledsandbox.fixture.scale;\n"
            f"public final class ScaleActivity{index:03d} extends ScaleActivityBase {{ }}\n",
        )

    activities = []
    for index in range(SCALE_COUNT):
        name = f"com.warden.controlledsandbox.fixture.scale.ScaleActivity{index:03d}"
        attrs = ['android:exported="false"']
        if index >= 64 and index <= 79:
            attrs.append('android:launchMode="singleTop"')
        elif index >= 80 and index <= 95:
            attrs.append('android:launchMode="singleTask"')
        elif index >= 96 and index <= 111:
            attrs.append('android:launchMode="singleInstance"')
        if index >= 112 and index <= 119:
            attrs.append('android:theme="@android:style/Theme.Translucent.NoTitleBar"')
        if index >= 120 and index <= 123:
            attrs.append('android:documentLaunchMode="always"')
        if index == 124:
            attrs.append('android:taskAffinity="com.warden.controlledsandbox.fixture.scale.affinity.a"')
        if index == 125:
            attrs.append('android:taskAffinity="com.warden.controlledsandbox.fixture.scale.affinity.b"')
        if index == 0:
            attrs = ['android:exported="true"']
        activities.append(
            f'        <activity android:name="{name}" ' + " ".join(attrs) + " />"
        )
    activities.append(
        '        <activity android:name="com.warden.controlledsandbox.fixture.scale.ScaleResultCallerActivity" '
        'android:exported="true" />'
    )
    write(
        FIXTURE_ROOT / "src/main/AndroidManifest.xml",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name="com.warden.controlledsandbox.fixture.scale.ScaleApplication"
        android:allowBackup="false"
        android:label="CAS Activity Scale Fixture"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name="com.warden.controlledsandbox.fixture.scale.ScaleActivity000" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
"""
        + "\n".join(activities[1:])
        + """
    </application>
</manifest>
""",
    )


def main() -> int:
    generate_translucent_stubs()
    generate_window_stubs()
    delete_index_coupled_stubs()
    rewrite_runtime_manifest()
    generate_scale_fixture()
    print("bounded activity stubs and scale fixture generated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
