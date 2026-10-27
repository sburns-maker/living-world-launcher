# Living World Home

A native Android home-screen launcher designed to place the fictional Living World alongside your real Android apps. Independent of Lovable and the Living World web repository.

## Prototype features
- Opt-in Android HOME activity: can be selected as the phone's home launcher in system settings.
- Device clock and date (refreshed upon opening Home).
- Living World shortcut pointing to the current web app.
- Locally populated installed-app drawer, search, and launch using Android LauncherApps.
- Home settings shortcut to switch back to Samsung One UI Home.

## Develop and build
1. Open this repository as an Android Studio project.
2. Install Android SDK 35 and use JDK 17.
3. Sync Gradle, build, and run on an Android test device.
4. Only after testing, choose **Living World Home** under Android's default Home app settings. Samsung's original launcher remains available.

This prototype is **not yet compiled or device-tested**. The full native experience, wallpaper/widget support, native notifications, Android audio routing, and Living World integration remain future phases.

## Self-hosting
Clone on the BurnsandBurns Ubuntu machine when convenient:

```bash
cd ~/projects
git clone git@github.com:sburns-maker/living-world-launcher.git
cd living-world-launcher
```

A server can host CI/builds/artifacts and optional synchronization services. **The launcher runs locally on Android**, not on the server. The project does not require Lovable.

## Permissions & privacy
No blanket QUERY_ALL_PACKAGES permission, contacts access, SMS access, notification-reading permission, or upload of installed-app inventories. Real apps open through Android, not within the simulation.
