# T57-R03-P1A Manifest / PackageParser

RESULT: PASS

CAS keeps BinaryXmlManifestParser + typed ManifestModel. P1A filled the remaining
package/runtime-visible modern fields instead of dumping unused attributes.

## Added typed fields

- versionName, compileSdk, sharedUserId, installLocation, isolatedSplits
- uses-feature
- application/manifest property
- intent-filter order / autoVerify
- data pathSuffix, advancedPattern, mimeGroup, ssp / sspPrefix / sspPattern
- component visibleToInstantApps, attributionTags, lockTaskMode, maxRecents,
  turnScreenOn, showWhenLocked

## Matrix

`docs/package/T57_R03_MANIFEST_SURFACE_MATRIX.yaml`

IMPLEMENTED=52 PARTIAL=0 MISSING=0 NOT_NEEDED_WITH_REASON=12

NOT_NEEDED items are backup/locale/profileable/legacy storage/overlay/key-set/
supports-screens. They are not consumed by Virtual PMS or guest runtime in P1.

## Corpus

Domain SelfTest BinaryXml fixtures cover:

- minimal + permission/library/instrumentation
- activity/alias merge
- service isolated process
- provider grant/path-permission
- queries already covered by existing tests
- modern intent-data and uses-feature/property

No external APKs.

## Differential

Public PackageManager comparison on RD API32 is scheduled with P1B PMS
observables. Parser/model equality is proven by BinaryXml fixture SelfTest.

VA Pro: NOT_PROVEN
