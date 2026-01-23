# Headunit Revived

# Playstore
I try to bring the app to the playstore! This is my first time and it is very hard to get through all the steps.
The next step is to get a several amount of testers.

Please go to groups.google.com and join the Group: headunit-revived@googlegroups.com
https://groups.google.com/g/headunit-revived

After that you have access to: https://play.google.com/apps/testing/com.andrerinas.headunitrevived
<p align="center">
<img width="200" height="200" alt="Image" src="https://github.com/user-attachments/assets/b7a9caad-00b1-4b73-b338-b2970ddcc855" />
</p>

<p align="center">
    <img src="https://github.com/user-attachments/assets/20c3d622-89dc-4c20-8eae-b43074f3c144"
    alt="Headunit Logo"
    height="200">
</p>

This project is a revived version of the original headunit project by the great Michael Reid. The original project can be found here:
https://github.com/mikereidis/headunit

## Screenshots
<img width="1280" height="800" alt="image" src="https://github.com/user-attachments/assets/22abbc13-75d5-436f-b0ae-2e92b7648d50" />
<img width="1280" height="800" alt="image" src="https://github.com/user-attachments/assets/f81149b3-a844-4657-87d2-a2867a5eb030" />
<img width="1280" height="800" alt="image" src="https://github.com/user-attachments/assets/140bbfdb-5b4f-4d49-a419-85aa91b48371" />

## How to use
### Wired USB Connection
- Connect your Android device (phone) to the tablet running Headunit Revived via USB cable.
- Make sure that Android Auto is installed on your phone.
- Set your phone to Host-Mode if nescessary and select Android Auto
- Click the USB Button in Headunit Revived, find your phone and click the right button to allow connection
- Click on your phone in the list and wait for Android Auto to start

### Wireless Connection (Experimental)
- Open Android Auto Settings on your phone
- Click on Version and then on version and permission and click several to enable developer settings
- Go back to Android Auto, click the three dots on the top right and start the infotainment server
- Now you need to bring your phone and headunit(tablet) into the same network (WiFi). You can use mobile hotspot from phone, or from the headunit or use wifi direct. That doesn't matter
- Go to the WiFi Menu in the Headunit Revive App
- Now you can make 2 things
- First: click on the scan button in the top right corner and let the app find your phone by itslef
- Or: manually add your phones ip address via "Add new" button
  - Go to your phones wifi settings and search for your IP-Address eg: 192.168.1.25
  - Add the IP-Address of your phone and click on it to start Android Auto
  - IMPORTANT: In my tests it only works, if your phone is unlocked AND shows the Android Auto settings page! Otherwise it won't connect

### Wireless Launcher
The app even works with wireless launcher (https://play.google.com/store/apps/details?id=com.borconi.emil.wifilauncherforhur)
- Go to settings and enable Wireless Headunit Server
- Both devices have to be in the same network (WiFi). You can use mobile hotspot from phone, or from the headunit or use wifi direct. That doesn't matter
- Open Wireless Launcher on your phone and start it manually (or select your cars BT connection for example as a starter)
- The Launcher App will find your headunit a send a connection request to your headunit and start the connection (my headunit in my car has very bad wifi and needs 5 minutes to connect to a hotspot. So wireless in not always a good decision)

### Connect Wirelessly via Intent (Power Users)
You can trigger a wireless connection attempt using an Android Intent. This is useful for automation tools like **Tasker**, **MacroDroid**, or via **ADB**.

**URI Scheme:** `headunit://connect?ip=<PHONE_IP>`

**Example ADB Command:**
```bash
adb shell am start -a android.intent.action.VIEW -d "headunit://connect?ip=192.168.1.25"
```

## Known Issues
- Often the wireless connection won't start. I need more debugging why
- Handshake failed sometimes and the devices won't connect. You need to try again, restart phone or clear caches

## Next Steps:
- Bugfixing 
  - very important are the connection issues!
  - Non-Fullscreen Mode is buggy (https://github.com/andreknieriem/headunit-revived/issues/49)
- Portrait Mode (https://github.com/andreknieriem/headunit-revived/issues/38)
- Maybe:
  - Android SKD 17

## Known Issues
- **Google Maps in Portrait Mode:** Touch interactions (searching, scrolling) within Google Maps may not work as expected when using Portrait Mode. While visual feedback (like ripple effects) might appear, the map itself may remain unresponsive. This appears to be an internal Android Auto / Google Maps limitation or bug in vertical orientations.

## Changelog
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

### v.1.8.1
- Fixed Fullscreen/Non-Fullscreen layout issues (black bars, overlapping)

### v.1.8.0
- Added Audio Sink Setting (Enable/Disable routing audio to HU)
- Added AAC Audio Support Setting (Experimental)
- Fixed audio stuttering issues by reverting buffering logic to v.1.4.1 defaults
- Restored robust video decoder logic (SPS Parsing) to fix black screen/crashes on Mediatek devices
- Fixed visual glitches on navigation bar and fullscreen transitions
- Improved list item UI with better click feedback
- Fixed SSL decryption crash (ArrayIndexOutOfBoundsException)

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

## Contributing

Creating release apk needs a keystore file. You can create your own keystore file using the following command in root folder:
`keytool -genkey -v -keystore headunit-release-key.jks -alias headunit-revived -keyalg RSA -keysize 2048 -validity 10000`  

After that you need to set the env variables depending on your OS:
MAC:
open ~/.zshrc or ~/.bashrc

`sudo nano ~/.zshrc or sudo nano ~/.bashrc`   
`export HEADUNIT_KEYSTORE_PASSWORD="YOUR_KEYSTORE_PASSWORD"  
export HEADUNIT_KEY_PASSWORD="YOUR_KEY_PASSWORD"`  

## Original Headunit
Headunit for Android Auto (tm)

A new car or a $600+ headunit is NOT required to enjoy the integration and distraction reduced environment of Android Auto.

This headunit app can turn a Android 4.1+ tablet supporting USB Host mode into a basic Android Auto compatible headunit.

Android, Google Play, Google Maps, Google Play Music and Android Auto are trademarks of Google Inc.