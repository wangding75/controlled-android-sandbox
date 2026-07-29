# M5-T4 Native Network, Loader and Diagnostics Closure

M5-T4 closes the remaining source-level Native network, dynamic-loader and Crash/ANR gaps without expanding the device evidence boundary.

## Network

The native hook surface now covers socket ownership, bind/connect, datagram send/receive, local/peer identity and sensitive socket options. All state is bounded and tied to the configured virtual network identity. Unknown or Host-sensitive behavior fails closed.

## Loader

Guest libraries are checked for path ownership and ELF ABI compatibility. `android_dlopen_ext` inputs are validated before entering the platform loader, including FD offsets, RELRO, reserved-address flags and namespace ownership.

## Diagnostics

Fatal native signals use an alternate signal stack and emit bounded virtual-context evidence. Java diagnostics track ANR episodes and export rotated files with hashes. Real Android tombstone and AMS evidence remain device-gated.
