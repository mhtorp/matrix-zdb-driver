# Matrix ZDB 5100 drivers for Hubitat

Hubitat Elevation drivers for the Logic Group **MATRIX ZDB5100**. The ZDB5100 is a Z-Wave wall switch with four buttons, each with an RGB LED, and a built-in dimmer.

| Driver | File | Device |
| --- | --- | --- |
| Matrix ZDB 5100 | `matrix_zdb.groovy` | The physical device: button events, device-wide settings, associations |
| Matrix ZDB 5100 (Button) | `matrix_zdb_button.groovy` | Child device for each button (endpoints 1-4): on/off and LED colour |
| Matrix ZDB 5100 (Dimmer) | `matrix_zdb_dimmer.groovy` | Child device for the dimmer output (endpoint 5) |

The drivers are written for hubs running the Z-Wave JS stack. They should also work on the older Hubitat Z-Wave stack.

## Installation

1. In **Drivers Code**, add all three drivers. Use **Import** with these URLs:
   - `https://raw.githubusercontent.com/mhtorp/matrix-zdb-driver/master/matrix_zdb.groovy`
   - `https://raw.githubusercontent.com/mhtorp/matrix-zdb-driver/master/matrix_zdb_button.groovy`
   - `https://raw.githubusercontent.com/mhtorp/matrix-zdb-driver/master/matrix_zdb_dimmer.groovy`
2. Include the ZDB5100. Remove the button cover and press the small centre button once; the button 1 LED starts blinking. Include with S2 security if your hub offers it.
3. If the hub did not pick the driver itself, set the device's type to **Matrix ZDB 5100** and click **Configure**.

On **Configure**, the parent creates four button children and one dimmer child. It then reads firmware and association information and applies your settings.

## Using the devices

**Buttons (parent device).** These events come from the parent device:

- `pushed`
- `held`
- `released`
- `doubleTapped`

Each event's value is the button number, 1-4. Use the parent device for button rules. `pushed` comes from Central Scene when the device sends it for single presses (1.05 and 1.09 do). The driver checks this on the first press. Otherwise it comes from the button's switch report, as long as **Send pushed events from switch reports** is on (the default).

**Button children.** On/off sets the button's state or LED, depending on **On/off commands from the hub control**. The default is the LED only. In that mode the child's switch shows the LED after a hub command and the button's state after a press or a refresh.

- `setColor`, `setHue` and `setSaturation` set the LED colour until the next press. **Enhanced LED control** must be enabled on the parent; it is by default.
- `flash` blinks the LED in its current colour.
- `setColorOn` and `setColorOff` change the colours the LED shows in the on and off states.

**Dimmer child.** It supports `on`, `off`, `setLevel` (with optional duration), `startLevelChange` and `stopLevelChange`.

- `on`, `off` and `setLevel` without a duration use the on/off duration preference (parameter 3). The dimming duration (parameter 2) is used when a button is held and for `startLevelChange`.

- `presetLevel` sets a level without turning the light on:
  - **Turn-on level preference set:** `presetLevel` changes the preference. Every turn-on uses the new level until the next `presetLevel`.
  - **Preference empty:** `presetLevel` applies to the next turn-on only, by `on()` or a button. After that the light turns on at its last level.

  `setLevel` never uses up the preset and never writes a parameter. With firmware 1.08 or later the device applies the level from parameter 9, to both `on()` and buttons. Older firmware ignores parameter 9, so only `on()` uses it there. If the light is on, `presetLevel` also sets the level now.
- **Fixed turn-on level** works the same way: from the hub on any firmware, and also from the buttons with firmware 1.08 or later.
- **Minimum level** and **Maximum level** are applied by the device: it spreads 1-99% over that range, so Hubitat levels are passed on unchanged.
- **Buttons that control the dimmer** chooses which buttons operate the dimmer locally.

**Generic component drivers.** The parent uses Hubitat's standard component interface. You can switch a child to **Generic Component Switch** or **Generic Component Dimmer** if you don't need its device-specific settings.

## Settings and parameters

The device's configuration parameters are split across the three devices:

| Device | Parameters |
| --- | --- |
| Parent | 7, 8, 10-15: Central Scene, double-click, enhanced LED control, button timing, LED colour balance, secure associations |
| Button *n* | 16-23 (button 1), 24-31 (button 2), 32-39 (button 3), 40-47 (button 4): function, timer, group 4 levels, on/off behaviour, LED mode, colours |
| Dimmer | 1-6 and 9: controlling buttons, durations, dimming mode, min/max level, turn-on level |

A parameter is only written when you set it, so empty settings leave the device's own value alone. Clearing a setting stops the driver from managing it, and the device keeps its current value. **Configure** writes every parameter you have set again and reads it back. The driver sets two parameters itself:

- Enhanced LED control is turned on.
- The LED colour balance is set to `FF5555`, which gives a more neutral white.

The parent's `syncStatus` attribute shows `synced`, or how many parameter changes the device has not yet confirmed. **Refresh Parameters** reads all 47 parameters back from the device.

> **Leading-edge loads:** set the dimmer's **Dimming mode** to *Leading edge* for iron-core (wire-wound) transformers. Trailing edge can damage the dimmer with these loads.

## Associations

The ZDB5100 can control other Z-Wave devices directly through association groups. Use **Set Association Group** on the parent with a group number and a comma-separated list of node IDs in hex. Write `NODE-EP` to target an endpoint, for example `1A, 2B-1`. An empty list clears the group. Each group holds up to 5 members.

| Group | Sends |
| --- | --- |
| 2, 5, 8, 11 | Basic Report with the state of button 1, 2, 3, 4 |
| 3, 6, 9, 12 | Binary Switch Set from button 1, 2, 3, 4 |
| 4, 7, 10, 13 | Multilevel Switch Set / level change from button 1, 2, 3, 4 |
| 14 | Basic Report with the dimmer level |

Group 1 is the lifeline and is managed by the hub. The current members of each group are listed in the parent's device data (`associationGroupN`).

A warning in the log may say the hub is not in the lifeline as a multi-channel association. In that case the driver cannot tell which button or the dimmer a report came from. Click **Repair Lifeline** to fix it.

## Upgrading from version 1.x

Update all three drivers together, then click **Configure** on the parent. Then click **Save Preferences** on each child.

- **Kept:** driver names and device network IDs, so existing devices and rules keep working.
- **Settings are not migrated:** settings have new names, so enter them again on the parent and each child. Until then, the device keeps the values written by 1.x, except Enhanced LED control, which goes back to the driver default (on).
- **Pre-staging** (`presetLevel`) now uses parameter 9, the device's turn-on level. Version 1.x wrote the group 4 level of a chosen button (parameters 18/26/34/42), which only affects devices associated in group 4. That level stays on the device; set the button's group 4 levels on the button child to change it.
- **Removed commands:** `setDefaultAssociations` and `processAssociations` are replaced by **Set Association Group**, which now takes a node list per group. The non-standard `trippleTapped` event has been removed.
- **Dimmer levels:** version 1.x also rescaled levels to the minimum/maximum range, on top of the device's own scaling. With a minimum or maximum set, the same level in a rule now comes out brighter than with 1.x. Check scenes and rules that use low levels.
- **Debug logging** is now off by default and turns itself off after 30 minutes.

## Testing

`test/run-tests.sh` runs the Spock suite in `test/MatrixZdbSpec.groovy` inside Docker. It only needs Docker and network access.

The suite loads all three drivers into [hubitat_ci](https://github.com/biocomp/hubitat_ci), a community mirror of the Hubitat driver sandbox and API. Parent and children are wired together the way the hub connects them. Every Z-Wave factory, command class and field the drivers use is checked against the mirror of the real Hubitat API. The tests cover:

- events from button and dimmer reports;
- outgoing commands;
- parameter encoding and sync;
- associations.

The radio, the hub scheduler and Z-Wave JS are not simulated.

The script pins hubitat_ci to a fixed commit and patches gaps where it lags behind the hub. These are listed at the top of the script:

- driver `settings`
- map-style command arguments
- the `Flash` and `LevelPreset` capabilities

```sh
test/run-tests.sh          # HTML report in test/report/index.html
```

## Changelog

### 2.0.0

- Rewritten in a table-driven style, based on the manual (firmware 1.08) and the Z-Wave JS device file.
- Children use Hubitat's standard component API (`componentOn`, `componentSetLevel`, ...) and receive events through `parse(List<Map>)`.
- Parameters are only written when set and are confirmed by reading them back (`syncStatus`). **Refresh Parameters** reads all of them.
- Fixed:
  - `setLevel(100)` sent the out-of-range value 100.
  - `setLevel(0)` with a minimum level set did not turn the dimmer off.
  - `on()` could send level 100 instead of restoring the last level.
  - The dimmer sent a spurious `pushed` event for button 5.
  - Firmware version was never recorded.
  - Association bookkeeping mixed strings and lists.
  - Dimmer levels were scaled twice when a minimum or maximum level was set.
  - In the default LED-only mode, turning a button child off read back the button's own state and flipped the switch back on.
  - Configure created duplicate children when the parent's network ID had changed since they were created.
- Supervision reports are now sent immediately instead of from a scheduled job.
- `pushed` comes from Central Scene when the device sends it for single presses, detected automatically.
- A dimmer change from a button pressed within a few seconds of a hub command is now reported as `physical`.
- After a firmware upgrade, **Configure** reads the new version and re-applies the children's settings.
- During a dimmer transition, the target level is shown straight away.
- `presetLevel` changes the **Turn-on level** preference when that is set (instead of being ignored), and is a one-off level otherwise. `setLevel` does not use up a one-off preset. Both also work on firmware before 1.08, when turned on from the hub.
- Added:
  - Physical/digital event types for the dimmer.
  - Suppression of the `pushed` event after a hold.
  - `setHue`, `setSaturation` and colour attributes on buttons.
  - Settings for every button and dimmer parameter in the manual.
  - Lifeline check.
- Removed SmartThings-era leftovers, unused state and the manual association queue.
- Added a test suite that runs the drivers in hubitat_ci (`test/run-tests.sh`).

## References

- [MATRIX ZDB5100 user manual (Danish)](https://logic-group.dk/wp-content/uploads/MATRIX-ZDB5100-brugervejledning-1-1.pdf)
- [Z-Wave JS device file for the ZDB5100](https://github.com/zwave-js/zwave-js/blob/master/packages/config/config/devices/0x0234/zdb5100.json)
