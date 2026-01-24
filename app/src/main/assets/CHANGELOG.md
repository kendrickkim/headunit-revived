# Changelog
### v.1.10.2
- Bigfix - Button Mapping ignored #71
- New Feature: Screen-Orientation Feature to lock to a certain orientation (Landscape/Portrait) #69 thanks to @JanRi3D
- Enhancement: SSL will now attempt multiple times and not break instantly thanks to @MicaelJarniac
- Added: Chinese(Tradionnal) translation 🇹🇼 thanks to @GazCore
- Added: Polish translation 🇵🇱 thanks to @Kacper1263
- Fixed brazilian portuguese folder name

### v.1.10.1
- Bugfix: Added missing 3 Byte startcode which stops some devices to start the projection
- Added PR #68 - Fix Wifi Direct detection thanks to @rakshan-kumr
- Added PR #67 - Brazilian Portuguese translation 🇧🇷 thanks to @MicaelJarniac
- Added PR #66 - Add conscrypt to fix error 7 on lower Android versions 🚀, thanks to @JanRi3D
- The old jni files and c code can maybe be removed when PR #66 is performing great. So we can get rid of that again :)

### v.1.10.0
- New Feature: Portrait Mode Support (Dashboard & Projection) with smart resolution scaling Known Bug is, that map is unresponsive to touch. That is in all HU apps
- New Feature: Redesigned Keymap Screen (easier configuration)
- New Feature: Right Hand side driving setting (#63)
- New Feature: Auto-Connect last session (Thanks to @JanRi3D) (#21)
- New Feature: Auto-Selfmode if enabled in settings
- New Feature: Allow sideloaded apps (#57)
- Localization: Added German Translation 🇩🇪 Other translations are highly appreciated
- Improvement: TextureView is now the default renderer (better compatibility for most devices)
- Improvement: Fixed Dashboard layout rotation
- Rewrite: Completly Rewrite the Video-Decoder as it was undebuggable. Removed the async mode and more

### v.1.9.0
- New Feature: GLES20 Video Renderer (Fixes black screen/artifacts/scaling on older Head Units)
- New Feature: In-App Log Export (Save to file/Share) for easier debugging
- Improvement: Audio Sink Logic fixed (System Audio always advertised) -> Fixes black screen when Audio Sink is disabled
- Improvement: Video Decoder optimized for legacy devices (Buffer size adjustments, Overflow handling)
- Hopefully Fix: Audio Stuttering resolved (Buffer/Queue logic reverted to stable state)
- Fix: Video Fragmentation on some devices (Support for split frames/Offset 2 headers)
- Fix: Crash on Android 5.1 (NoSuchMethodError)
- Fix: Audio-Sink disable not working
- UI: Consistent Dialog Theme (Teal/Rounded) and improved list buttons
- Compatibility: Verified support for Android 4.1+ (minSdk 16)
- Compatibility: Bring back native SSL Support (JNI) for better performance on older devices (Toggle in Debug Settings)

### v.1.7.0
- Added WiFi Network Discovery (Port Scan) with Auto-Connect
- Added Intent Support (`headunit://connect?ip=...`) for automation
- Added Wifi-Launcher Support with new setting
- Updated README

### v.1.6.3
- Added mandatory Safety Disclaimer on first start
- Improved audio stability and fixed stuttering issues
- Enhanced full-screen stability with transparent system bars
- Fixed WiFi disconnection synchronization issues (ByeBye request)
- General UI and stability improvements

### v.1.6.2
- Fixed critical screen flickering during startup and fullscreen transitions
- Resolved video decoder freezing issues on tablets and older devices
- Improved system bar handling for a more stable projection experience

### v.1.6.1
- Added "About" page with version info, changelog, and license
- Added "Force Legacy Decoder" (synchronous mode) setting to fix issues on some devices (e.g., Mediatek)
- Improved surface handling to prevent crashes on decoder reconfiguration
- Fixed "Unsaved changes" dialog in settings
- Updated UI with consistent back arrows and theming
- Fixed black screen issues on some devices by optimizing decoder initialization

### v.1.6.0
- Fixed the selfmode not working outside the wifi bug
- Redesign of App, Look and feel with modern Material 3
- Better Settings
- Huge Android Auto Protocol Updates
- Clicking Exit in Android Auto now closes the projection

### v.1.5.0
- Complete Rewrite of the Video decoder for better Video-Performance
- Updated Android-Auto Protocol with the latest available codecs (h265 for example)
- Added 1440p Video-Option(Note this only works with h265!)
- Added FPS-Setting
- Added Codec-Setting
- Added Force to Use Software decoder Setting
- Merged the Android Native SSL Library and get rid of the old jni files

### v.1.4.1
- Fixing Touch-Events for devices with higher resolutions
- Removing file-log and logging is only enabled if debug is on

### v.1.4.0
- Added Selfmode
- Better Close App

### v.1.3.0
- Changed the Settings Layout Look and feel
- Added DPI Option
- Added full screen option
- Fixing Keymap Changes and button recognition

### v.1.2.1 - Resolution enhancement
- Just a minor enhancement for the resolution. Not yet perfect in my opinion but better than before
- The is the last release for this year. Happy Holidays to all and a happy new year

### v.1.2.0 - Bugfix Release
- Added Exit button to app
- Added resolution settings back for better compatibility with different screen sizes
- Added Option for which texture to use. Some devices perform better on SurfaceView, some on TextureView
- Fixed keymapping
- Fixed a lot of color issues
- Fixed a bug where the app crashed on startup on some devices
- Fixed Layout on wider screens
- Some rewrite, and small bugfixes

### v1.1.0 - New Design
- Changed the basic design to a modern look and bigger buttons
- Hopefully fixed audio-stutters with audio thread and some logs
- Removed some deprecations

### v1.0.0 - Initial Revived Release
- Updated dependencies to latest versions.
- Improved compatibility with newer Android versions.
- Added Multitouch-Support
- Some sort of wireless support with Headunit-Server on Phone
