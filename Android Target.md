# Android Target

This folder is the planned Android-native target application.

Core responsibilities:
- Request Android permissions during first-time setup.
- Store only the permission state needed by the app.
- Register the device with Supabase.
- Send online/offline status.
- Receive authorized commands.
- For location, use Android's location APIs according to the permission granted.
- Camera/microphone actions must remain visible and permission-controlled by Android.

The actual Gradle/Android Studio project will be generated in the next step so we can choose the Android package name and minimum Android version together.
