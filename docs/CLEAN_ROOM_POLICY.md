# Clean-room development policy

1. Production source files must be authored from Android SDK/AOSP behavior, public specifications, and black-box tests.
2. Code from VirtualApp, NewBlackbox, Twoyi, leaked commercial SDKs, or unknown-license forks must not be copied, translated, mechanically rewritten, or submitted through generated patches.
3. External projects may be used by a separate research role to identify observable requirements. Research output must be behavior-oriented and must not contain source fragments or file-by-file implementation recipes.
4. Every third-party dependency requires a recorded source, version, license, and reason for use.
5. New source files use SPDX identifier `Apache-2.0` in the release branch after the repository's header automation is introduced.
6. Compatibility fixes must cite the relevant Android API/AOSP behavior or an independently reproducible test case.
7. Code similarity scans should be run before public or commercial release.

This policy reduces copyright risk but is not a legal opinion. Distribution and license decisions require review by qualified counsel.
