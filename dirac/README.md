# Optional Nothing Dirac audio

Standalone port of AOSPA Phone2's Dirac service, native effects and resource
archive. This branch does not enable Dirac automatically or alter the
hardware repository's existing `config.mk`.

## Integrating into another ROM

1. Check out this repository at `hardware/nothing` on branch `dirac`.
2. In the device product makefile, add:

   ```make
   $(call inherit-product, hardware/nothing/dirac/dirac.mk)
   ```

3. Merge the library and effect entries from this directory's
   `audio_effects.xml` into the audio effects XML actually loaded by your
   vendor audio HAL. For volcano, check both `/vendor/etc/audio_effects.xml`
   and `/vendor/etc/audio/sku_volcano/audio_effects.xml`. Keep the ROM's other
   effects and recording preprocessing. Do not replace its full XML with
   this two-entry reference file.
4. Exclude the Dolby product include, services and audio effect entries
   from that test build. This package does not arbitrate with Dolby or
   provide a runtime switch between the two processors.
5. Use a 64-bit vendor audio HAL supporting the legacy audio effect ABI.
   The blobs export `AELI`; they are not native AIDL effect implementations.
   The SELinux include expects the Qualcomm `vendor_audio_prop` type.

The prebuilt definitions use their own Soong namespace. Do not also import
another copy of `libdirac`, `libdiraceffect_NT` or
`ParanoidDiracEffectService` into the same product. No extra proprietary
vendor repository or audio HAL replacement is required for this package.

## Behaviour and limitations

There is no launcher app or settings screen. A platform-signed background
service creates a global Dirac effect, selects MUSIC tuning, follows media
volume, and enables it only for built-in speaker/earpiece media routes.
Headphones, USB and Bluetooth media routes bypass it. Routing decisions use
media audio attributes; call behaviour has not been validated. There is no
equalizer UI.

**The resource archive and interface contain Phone2 calibration.** They
are not Phone3a calibration. Sound quality, loudness and speaker protection
have not been validated on Phone3a or another device. Do not raise mixer
gain to compensate. Inclusion does not establish that Dirac sounds better
than Dolby.

Static checks cover dependencies, effect UUID, product paths, configuration
and property labels. No ROM build, policy compilation or device playback
testing has been performed. When testing your build, check effect creation,
volume behaviour and external-device bypass before judging the sound.

## Credits and provenance

- Service by **Omkar Chandorkar <gotenksIN@aospa.co>**, Paranoid Android:
  [phone2: Add ParanoidDiracEffectService](https://github.com/AOSPA/android_device_nothing_phone2/commit/18fd6367f44babf73ac234c72bc8cd38fe6ae180).
  Original source copyright and SPDX notices are retained.
- Phone2 interface and configuration reference:
  [AOSPA snapshot 9c29bf25a389](https://github.com/AOSPA/android_device_nothing_phone2/tree/9c29bf25a389).
- Native libraries and calibration are proprietary Nothing/Dirac artifacts,
  sourced from
  [ThankYouMario's Phone2 vendor snapshot](https://github.com/ThankYouMario/proprietary_vendor_nothing_phone2/tree/f1fa4810eaee35fa9213e95e1ccf92d17af75839).
  Their original ownership is retained; source-code SPDX notices do not
  relicense the proprietary artifacts.
- Standalone hardware-tree packaging by **Fuzail Mansuri**.

`provenance.json` lists the pinned blob revision and SHA-256 hashes. Each
listed path can be restored from the same path in that vendor snapshot.
