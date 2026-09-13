# Privacy Policy

**Last Updated:** September 13, 2026

This Privacy Policy explains how **MaybeItsSoftware Ltd** ("we", "us", or "our"), a company registered in the United Kingdom, handles your information when you use **London Dock Compass** (the "App") on Wear OS watches and Android phones. MaybeItsSoftware Ltd is the data controller for any personal data described in this policy.

The current version of this policy is published at [maybeitssoftware.co.uk/london-dock-compass/privacy](https://www.maybeitssoftware.co.uk/london-dock-compass/privacy).

The App has no accounts, no analytics, no advertising and no crash reporting. We do not operate any server that receives data from the App.

---

## 1. Information We Do Not Collect (No Personal Data)

We do not collect, store, or transmit any of your personal data to servers owned or operated by MaybeItsSoftware Ltd.

* We do not collect your name, email address, phone number, contacts, or account credentials.
* There is no account registration or sign-in.
* There are no analytics, advertising, or crash-reporting services in the App.

---

## 2. Location Information

The App's core purpose is to point you to nearby Santander Cycles docking stations, so it asks for permission to use your device's location (`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`). You can use the App without granting it, but it cannot find docks near you.

* **On-device calculation:** Distance, compass bearing, and the ranking of nearby docks are calculated on your device.
* **Nearby dock lookup:** To fetch live availability, the App sends your current position to the Transport for London (TfL) BikePoint API, asking for docks within 800 metres of that point. See Section 4.
* **Last known position:** The App stores your most recent position on your device so the tile and watch face complication can still show nearby docks when the system does not hand them a fresh location. This is excluded from Android cloud backup and device-to-device transfer.
* **Location provider:** Your position is obtained through Google Play services location, which Google operates under its own privacy policy.

---

## 3. Device Sensors

On Wear OS, the App reads the device's rotation vector sensor (which combines compass, accelerometer and gyroscope readings) to turn the compass needle towards a dock. Sensor readings are used in memory only; they are never stored or transmitted.

---

## 4. Transport for London (TfL)

Live bike, e-bike and space counts come from the TfL BikePoint API (`api.tfl.gov.uk`). Requests to TfL contain:

* your current position and a search radius, to find nearby docks;
* the IDs of docks you have saved or pinned, to refresh their counts; and
* an application key that identifies the App, not you.

As with any internet request, TfL receives your device's IP address. Requests carry no account, name, or other identifier linking them to you. TfL handles this data under its own privacy policy, available on the TfL website.

Recent results are reused for a short time instead of repeating a request. If a request fails, the App falls back to cached results or to dock locations bundled with the App.

---

## 5. Local Data Storage

The following data is stored on your device only:

* **Preferences:** your chosen mode (bikes, e-bikes or spaces), saved docks, and pinned destination dock.
* **Dock cache:** the most recent availability results and the position they were fetched for, so the App works briefly offline. Excluded from backup and device transfer.
* **Last known position:** as described in Section 2. Excluded from backup and device transfer.

Your preferences (but not your position or the dock cache) may be included in your Google account's Android backup if you have backup enabled.

You can delete all of this data at any time by clearing the App's storage or uninstalling the App.

---

## 6. Watch and Phone Sync

If you use the App on both a paired phone and watch, your saved docks and pinned destination dock are synced between them using the Google Play services Wearable Data Layer. This contains dock IDs, dock names and dock positions, never your own position. The sync goes directly between your devices, or through Google's services when they are not connected nearby; it never passes through our servers.

---

## 7. Data Sharing and Disclosure

* **No selling:** We do not sell, trade, or rent your information.
* **Third parties:** The only third parties involved are TfL (Section 4) and Google Play services (Sections 2, 5 and 6), each acting under its own privacy policy.

---

## 8. Data Retention

We hold no data about you, so there is nothing for us to retain or delete. Data stored on your device remains there until you clear the App's storage or uninstall it.

---

## 9. Your Choices

You can revoke the App's location permission at any time in your device's settings (for example, **Settings > Apps > London Dock Compass > Permissions**). Because we hold no personal data about you, there is nothing for us to access, correct, or erase on request, but you are welcome to contact us with any question about your rights under the UK GDPR.

---

## 10. Children's Privacy

The App does not knowingly collect personal information from anyone, including children under 13.

---

## 11. Changes to This Privacy Policy

We may update this Privacy Policy from time to time. Changes are published at the address above, and the "Last Updated" date at the top of this policy is revised.

---

## 12. Contact Us

If you have any questions about this Privacy Policy, or wish to exercise your rights under the UK GDPR, please contact us:

* **Data Controller:** MaybeItsSoftware Ltd, United Kingdom
* **Email:** [privacy@maybeitssoftware.co.uk](mailto:privacy@maybeitssoftware.co.uk)
