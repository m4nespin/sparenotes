# Third-party notices

TrailSafe includes or depends on these components:

## Proton Drive CLI 0.6.0 and Proton Drive SDK

- Project: https://github.com/ProtonDriveApps/cli-drive
- Official ARM64 musl binary: https://proton.me/download/drive/cli/0.6.0/linux-arm64-musl/proton-drive
- License: MIT
- Official binary SHA-512: `34831CFCC0EA46C331BD48635E5B2E882483FDB70BFC4FC5C273C8CDD2CEDDB6026296F25C7222E4B235D8A88AA15669B6E1C9FF40B9A2AF081EE279B83289C2`
- TrailSafe-patched binary SHA-256: `23E372DF3F66CC625EC0A6962D331ACA8A22B1E22564B8F929A3C3760C1CD387`

TrailSafe changes the bundled CLI's desktop browser opener to return immediately. Authentication URLs and QR codes are presented by the Android UI instead. The reproducible patch is `tools/patch-proton-cli.ps1`.

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
