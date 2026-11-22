# Bus Inspector

A set of mobile-friendly report forms for bus operations — pre-trip checks,
condition reports, incident reports, and safety hazard reports — packaged
both as a standalone web app and as a native Android APK.

## Reports

- **Pre-Trip Inspection** (`pre-departure-check.html`) — pre-departure vehicle check
- **Bus Condition Report** (`bus-condition.html`) — vehicle condition and defects
- **Traffic Incident Report** (`incident-report.html`) — on-road incidents
- **Safety Hazard Report** (`safety-hazard-report.html`) — operator-reported hazards

`index.html` is the portal page linking out to each report.

## Android app

`app/` contains the native Android wrapper (package `com.businspector.app`,
currently v3.0.0) that loads the same HTML reports and adds a camera/email
bridge (`android-email.js`) for attaching photos and sending completed
reports without the interruption of handing off to a separate app.

`build-apk.ps1` is the Windows build script used to produce the signed
`bus_inspector.apk` committed in this repo for direct install/distribution.

## Running it

The web forms need no build step — open `index.html` (or any individual
report) in a browser. For the Android app, install `bus_inspector.apk`
directly, or rebuild it from `app/` with `build-apk.ps1` on a machine with
the Android SDK and a JDK set up per the paths at the top of that script.
