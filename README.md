# Geotag Camera (Kotlin)

Camera app that stamps each photo with a location QR code, latitude, longitude,
GMT time, local date and time, and the place name and address.

- CameraX for the camera, LocationManager for GPS, ZXing for the QR code.
- Address: Android Geocoder first, then OpenStreetMap (Nominatim) as a fallback.
- Photo and Video modes (tap "Mode"). Video records with the stamp burned into every frame (live clock), via CameraX OverlayEffect; videos save to Movies/GeotagCamera with the start location in the file metadata.
- Photos are saved to Pictures/GeotagCamera, with the GPS position, date and place also written into the photo's EXIF data.

## Build the APK with GitHub Actions
1. Upload everything in this folder to a GitHub repo (including `.github/workflows/build-apk.yml`).
2. Open the Actions tab, run "Build APK" (or push to `main`).
3. Download the `geotag-camera-apk` artifact, unzip it, and install `app-debug.apk`.
