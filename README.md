# waze-head-up-display
<br />
<br />
<img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/hud-night.png" width="100%" height="auto" />
<br />
<br />
Waze HUD (Head Up Display) provides navigation guidance, speed limit and current speed data from waze to a Head up display you could simply make by using an android phone. The repository contains the gradle projects for the android apps you need to build.
<br />
<br />
<h2>Screenshots from the apps</h2>
<table width="100%">
  <tr width="100%">
    <td width="20%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-175810.jpg" />
      <br />
      <br />
      <span>The phone running Waze and the data sender app - Simple turn indicator</span>
    </td>
    <td width="80%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-175809.jpg" />
      <br />
      <br />
      <span>The phone running the HUD receiver app - Simple turn indicator</span>
    </td>
  </tr>
  <tr width="100%">
    <td width="20%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-175535.jpg" />
      <br />
      <br />
      <span>The phone running Waze and the data sender app - Multiple lanes - single turn</span>
    </td>
    <td width="80%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-175534.jpg" />
      <br />
      <br />
      <span>The phone running the HUD receiver app - Multiple lanes - single turn</span>
    </td>
  </tr>
  <tr width="100%">
    <td width="20%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-175502.jpg" />
      <br />
      <br />
      <span>The phone running Waze and the data sender app - Multiple lanes - multiple turns</span>
    </td>
    <td width="80%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-175501.jpg" />
      <br />
      <br />
      <span>The phone running the HUD receiver app - Multiple lanes - multiple turns</span>
    </td>
  </tr>
  <tr width="100%">
    <td width="20%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-185945.jpg" />
      <br />
      <br />
      <span>The phone running Waze and the data sender app - Multiple lanes - multiple straight directions</span>
    </td>
    <td width="80%" valign="top">
      <img src="https://raw.githubusercontent.com/sorinbotirla/waze-head-up-display/refs/heads/main/images/Screenshot_20260720-185951.jpg" />
      <br />
      <br />
      <span>The phone running the HUD receiver app - Multiple laness - multiple straight directions</span>
    </td>
  </tr>
</table>
<br />
<br />
<h2>How it works</h2>
The project consists in 2 required android apps and another optional app used for debug purposes. The WazeDataSender_AndroidApp reads the screen UI content when running Waze, it is meant to be installed on an android phone or even on an Android head unit. It reads the direction arrows, multiple lanes indicators, current speed, speed limit and forwards everything to the second app, WazeHudReceiver_AndroidApp via WiFi. The WazeHudReceiver_AndroidApp is meant to be installed in another android phone that can act like a HUD display. You simply put that phone below the windshield and the image is reflected back to the driver. A HUD film can be applied on the windshield to prevent image ghosting. <br /><br />
The HUD Receiver app features display flip to compensate the windshield projected image reflection. <br /><br />
The flip icon used to flip the image. It also takes the rotation in consideration so you can test your best fit image flip before seating the phone. Make sure the phone is secured in place so it won't move when the car turns.<br /><br />
HUD App supports dragging and resizing of all the UI components, the direction indicators, driving speed and speed limit. You can place them as you need on the screen.
Both phones must be connected in the same wifi network. Use one as a hotspot for example.<br /><br /><br /><br />
<h2>How to install</h2>
<h3>The Waze data sender</h3>
Download the project then install Android Studio if you don't already have it installed.<br />
In android Studio, click Open, go to the folder WazeDataSender_AndroidApp and select it. <br /> <br />
Click the hammer icon from the left sidebar (build) and watch the progress of installing the dependencies until the final BUILD SUCCESSFUL message is shown.<br />
Click the 4 horizontal lines icon from the most top left area to open the menu, go to Build > Generate App Bundles or APKs > Generate APKs.<br > <br />
Wait for it to create the APK file. When finished, a small popup on the bottom right area of the screen will show up. Click locate to go to the folder where the APK was saved.<br />
In that folder the app-debug.apk is the freshly built android app ready to install in the phone (or Android head unit) that will run Waze. <br /> <br />
After installing, open the Waze Telemetry sender app. From there, enable accesibiltiy optiions (to allow screen content reading), enable floating overlay (to allow the speed limit red circle testing), start screen capture and open Waze. In Waze start navigating to a destination. If the floating red circle containing speed limit gets the value of the current speed limit from Waze, you're good to go to step 2. If it still shows "--" then you need to go to accesibility options and enable the service for waze telemetry sender. <br /><br />

If the option is disabled (in Android 13 and up), you need to go to the phone settings > Apps > Waze telemetry sender > click the 3 dots icon in the top right and enable restricted permissions. Then you go back to accesibility options and enable the service for Waze telemetry sender. <br /><br />
You can move the floating red circle to any convenient position.<br /><br /><br /><br />
<h3>The HUD receiver</h3>
Build the app in android studio like the previous app. Install the built app in the phone which will sit under the windshield as a HUD. Open the app and wait for data.
