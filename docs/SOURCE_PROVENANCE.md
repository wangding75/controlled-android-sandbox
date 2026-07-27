# Source provenance

## Original source

All production Java, AIDL, XML, Gradle and script files in this repository were created for the Controlled Sandbox clean-room foundation.

## External source code

No source files from VirtualApp, NewBlackbox, Twoyi, or their forks are present.

## Build-time tools

- Android Gradle Plugin 8.11.1, downloaded from Google's Maven repository during a normal online build.
- Gradle 8.13, downloaded from the URL in `gradle-wrapper.properties`.
- Android SDK Platform 36 and JDK 17.

These tools are not redistributed in the source ZIP. The included Gradle bootstrap JAR is compiled from the original source under `tools/wrapper-src`.

## Runtime libraries

Foundation 0.1 has no third-party runtime library dependency. It uses Android platform APIs and project-owned modules only.
