# Disclaimer and Terms of Use

Downloading, installing, or using GhostLock ("the Software") means you have read, understood, and accepted all of these terms. If you do not agree, stop using the Software and uninstall it.

This English text is a courtesy translation. The Chinese version (`LEGAL.md`) is the authoritative one; where they differ, the Chinese text governs.

## 1. Use at your own risk

The Software obtains temporary root by exploiting an Android kernel vulnerability and depends on a kernel race condition. It is inherently unreliable and carries risk, including but not limited to:

- Device heating, lag, or an unresponsive UI
- Kernel panic, unexpected reboot, or failure to boot
- Data loss, misbehaving apps, system instability
- Triggering vendor security mechanisms, affecting warranty or device integrity attestation

The Software is provided "AS IS", without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose, and noninfringement. To the maximum extent permitted by applicable law, the authors and contributors are not liable for any direct, indirect, incidental, special, or consequential damages arising from the use of, or inability to use, the Software.

## 2. Scope: your own or authorized devices only

- Use the Software only on devices you own, or for which you hold explicit written authorization from the device owner.
- Accessing or controlling someone else's device without authorization is illegal in most jurisdictions. The Software grants no license for such use.
- Root obtained through the Software lasts only for the current boot session and is gone after a reboot. The Software does not modify the boot image and provides no persistent root.

The license does not restrict fields of use: GPL-3.0 permits commercial use. The restrictions above are risk notices and compliance requirements on how you use the Software, not additional conditions on the license.

## 3. Third-party components are licensed separately

The ReZygisk and Vector (LSPosed) packages and the KernelSU manager APK ship with the Software. All three are GPL-3.0, the same license as the Software; see `NOTICE` for attribution, versions, and source locations. ZygiskNext is not distributed with the Software (its license forbids redistribution as of v4-0.9.2); its GPL-3.0 fork ReZygisk fills the same role. The upstream projects remain responsible for the behavior, security, and legality of their components, and the Software makes no representation or warranty about them.

The `libksud.so` bundled in the Software comes from upstream KernelSU and is GPL-3.0, as is the rest of the code; see `NOTICE` for attribution and source location.

The Software's own code is licensed under the GNU General Public License v3.0; see "Licenses" in the app.

## 4. Changes and jurisdiction

The Chinese text governs. The author may revise these terms at any time; a revision takes effect when the new version is published, and continued use after that constitutes acceptance. Disputes should first be settled amicably; failing that, they are subject to the courts with jurisdiction at the author's place of residence.
