# Third-party notices

SpareNotes includes or depends on these components:

## Proton Drive CLI 0.8.0 and Proton Drive SDK

- Project: https://github.com/ProtonDriveApps/sdk
- Official ARM64 musl binary: https://proton.me/download/drive/cli/0.8.0/linux-arm64-musl/proton-drive
- License: MIT
- Official binary SHA-512: `FB386CAB36BC346E8BAE1F3E79EFDD14810DE748E762A2C88F384016199FF7211304CC0EC4D220C260C67B83BBE4D3A8D4DD2A2EA0E93B9FDD25C1E42F448165`
- SpareNotes-patched binary SHA-256: `3987BB50B3B3D7AF801AC0ABAF740BCD7E188656B84BB57CF314D85D9EA6F093`

SpareNotes changes the bundled CLI's desktop browser opener to return immediately. Authentication URLs and QR codes are presented by the Android UI instead. The reproducible patch is `tools/patch-proton-cli.ps1`.

## musl libc

- Project: https://musl.libc.org/
- Source package: Alpine Linux 3.24 `musl`
- License: MIT

The loader and libc contain an Android compatibility patch changing the resolver path from `/etc/resolv.conf` to a private `resolv.conf` file.

## GCC runtime libraries

- Components: `libgcc_s.so.1`, `libstdc++.so.6`
- Source packages: Alpine Linux 3.24 `libgcc` and `libstdc++`
- License: GNU GPL with GCC Runtime Library Exception, version 3.1

## CA certificate bundle

- Component: Alpine `ca-certificates-bundle`
- Upstream certificates: Mozilla CA Certificate Program
- Source package: Alpine Linux 3.24 `ca-certificates`

## ZXing Core 3.5.4

- Project: https://github.com/zxing/zxing
- License: Apache License 2.0
