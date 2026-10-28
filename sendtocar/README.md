# Send to Car

Share a place on the phone, and Waze on the head unit starts navigating to it. One APK runs on
both devices: pick "car" or "phone" on first launch.

Each place is sent over three channels at once, and the car uses whichever arrives first:

| Channel | How |
| --- | --- |
| Wi-Fi | Car runs an HTTP server on port 47800; phone tries the last IP, then sweeps its local /24s (hotspot). |
| Bluetooth | Car listens on an insecure RFCOMM socket; phone tries each paired device. |
| Internet | ntfy.sh topic named from the pairing code; car acknowledges on `<topic>-ack`. |

A place sent as a text file by Bluetooth file transfer can also be read on the car (test 3).

## 0.1.0 is a probe

Before the final app, this version checks on the real unit that each channel works, that the
service can open the "Navigate to" card from the background (no notifications needed), and that
it survives ACC off/on. Run the tests on both screens, then send the car report (QR or internet).

Release: push a tag `sendtocar-v<versionName>`. It is published as a pre-release so Car Radio's
updater (which reads `releases/latest`) never picks it up.
