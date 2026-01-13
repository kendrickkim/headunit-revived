# Changelog

### v.1.7.0
- Added WiFi Network Discovery (Port Scan) with Auto-Connect
- Added Intent Support (`headunit://connect?ip=...`) for automation

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
