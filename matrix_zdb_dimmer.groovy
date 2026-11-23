/**
 *  Matrix ZDB 5100 (Dimmer) — child driver for the built-in dimmer (endpoint 5) of the Logic Group MATRIX ZDB5100
 *
 *  Created by the "Matrix ZDB 5100" parent driver.
 *
 *  Source and documentation: https://github.com/mhtorp/matrix-zdb-driver
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final List<Map> DIMMER_PARAMS = [
    [num: 2, name: "dimDuration", size: 1, title: "Dimming duration, 0-100%", range: "0..255",
     help: "Used when a button is held. 0 = instant, 1-127 = seconds, 128-255 = 1-128 minutes. Device default 5."],
    [num: 3, name: "onOffDuration", size: 1, title: "On/off duration", range: "0..255",
     help: "Used for on, off and setLevel without a duration. 0 = instant, 1-127 = seconds, 128-255 = 1-128 minutes. Device default 0."],
    [num: 4, name: "dimmingMode", size: 1, title: "Dimming mode",
     options: [0: "No dimming (on/off only)", 1: "Trailing edge (LED, electronic transformers)", 2: "Leading edge (iron-core transformers)"],
     help: "Device default: Trailing edge. Iron-core transformers must use leading edge, or the dimmer can be damaged."],
    [num: 5, name: "minLevel", size: 1, title: "Minimum level (%)", range: "0..99",
     help: "Actual output at the lowest level; the device spreads 1-99% over the minimum-maximum range. Device default 0; firmware 1.09 stores 0 as 1, so 0 is sent as 1."],
    [num: 6, name: "maxLevel", size: 1, title: "Maximum level (%)", range: "1..99",
     help: "Actual output at 99%. Device default 99."]
]

// When setLevel last started turning the dimmer on or changing its level, per device (see on()). Static rather than
// state: state is saved when a command finishes, and integrations send on() and setLevel() about 100 ms apart.
@Field static final Map<String, Long> LEVEL_SET_AT = new ConcurrentHashMap<>()
@Field static final Long LEVEL_SET_WINDOW_MS = 2000L

// Parameter 1 is a bitmask (button 1 = 1, 2 = 2, 3 = 4, 4 = 8); one option per combination.
@Field static final Map DIMMER_BUTTONS = (0..15).collectEntries { Integer mask ->
    List<Integer> buttons = (1..4).findAll { mask & (1 << (it - 1)) }
    [mask, mask ? (buttons.size() == 1 ? "Button " : "Buttons ") + buttons.join(" + ") : "None (hub control only)"]
}

metadata {
    definition(name: "Matrix ZDB 5100 (Dimmer)", namespace: "mhtorp", author: "Mathias Husted Torp",
               importUrl: "https://raw.githubusercontent.com/mhtorp/matrix-zdb-driver/master/matrix_zdb_dimmer.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "LevelPreset"
        capability "Refresh"
        capability "Configuration"
    }

    preferences {
        input "dimmerButtons", "enum", title: "Buttons that control the dimmer", options: DIMMER_BUTTONS,
            description: "Device default: Button 1."
        DIMMER_PARAMS.each { p ->
            Map opts = [name: p.name, title: p.title, description: p.help]
            if (p.options) {
                input(opts + [type: "enum", options: p.options])
            } else {
                input(opts + [type: "number", range: p.range])
            }
        }
        input "onLevel", "number", title: "Turn-on level (%)", range: "1..99",
            description: "Every turn-on uses this level. While it is set, presetLevel changes it; when empty, presetLevel only sets the next turn-on and the light otherwise turns on at the last level. Firmware before 1.08 only applies it when turned on from the hub."
        input "logEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
        input "txtEnable", "bool", title: "Enable description text logging", defaultValue: true
    }
}

/* ---------- Lifecycle ---------- */

void installed() {
    logInfo "installed"
}

void updated() {
    logInfo "preferences saved"
    if (logEnable) runIn(1800, "logsOff") else unschedule("logsOff")
    configure()
}

void configure() {
    List<Map> params = desiredParameters()
    // Clearing the fixed turn-on level must also clear it on the device, once.
    if (fixedOnLevel() == null && state.fixedOnLevelSet) {
        parent?.componentSendParameter(device, 9, 1, 0L)
    }
    state.fixedOnLevelSet = fixedOnLevel() != null && turnOnLevelSupported()
    parent?.componentSetParameters(device, params)
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// Also called by the parent during its configure(); must not call back into the parent.
List<Map> desiredParameters() {
    List<Map> result = []

    result << [num: 1, size: 1, value: toLong(settings.dimmerButtons)] // null = no longer managed

    DIMMER_PARAMS.each { p ->
        Long value = toLong(settings[p.name])
        if (p.num == 5 && value == 0L) value = 1L // the device reports 0 back as 1, which would never count as synced
        result << [num: p.num, size: 1, value: value]
    }

    // Parameter 9: 129-227 = level (1-99) + 128 at every turn-on. Empty = left to presetLevel (1-99 = next turn-on only).
    // Firmware before 1.08 stores it but ignores it; on() then sends the level itself.
    Integer fixed = fixedOnLevel()
    result << [num: 9, size: 1, value: fixed != null && turnOnLevelSupported() ? 128L + fixed : null]

    logDebug "desired parameters: ${result}"
    return result
}

/* ---------- Commands ---------- */

// Without a duration, on, off and setLevel use the on/off duration preference (parameter 3).

// Turns on at the turn-on level or the preset level if there is one, otherwise at the last level.
// A dimmer that is on, or that setLevel has just started turning on, keeps its level: integrations such as Google Home
// send on() and setLevel() together in either order, and the device would apply the turn-on level, or go to full
// brightness for a plain on during a fade.
void on() {
    if (device.currentValue("switch") == "on" || levelJustSet()) {
        logDebug "on(): already on, level kept"
        return
    }
    Integer fixed = fixedOnLevel()
    Integer preset = toInt(state.presetLevel)
    state.remove("presetLevel")
    if (preset && !fixed && turnOnLevelSupported()) {
        // Parameter 9 holds the preset: a plain on lets the device apply it, as for a button press,
        // and the device then goes back to remembering the last level.
        logDebug "on() at the preset level ${preset}% (parameter 9)"
        parent?.componentOn(device, onOffSeconds())
        return
    }
    Integer level = fixed ?: preset
    if (level) {
        logDebug "on() at ${level}%"
        parent?.componentSetLevel(device, deviceLevel(level), onOffSeconds())
    } else {
        logDebug "on()"
        parent?.componentOn(device, onOffSeconds())
    }
}

void off() {
    logDebug "off()"
    LEVEL_SET_AT.remove(deviceKey())
    parent?.componentOff(device, onOffSeconds())
}

void setLevel(level, duration = null) {
    Integer l = clamp(toInt(level) ?: 0, 0, 100)
    Integer value = deviceLevel(l)
    def secs = duration != null && duration.toString().trim() != "" ? duration : onOffSeconds()
    logDebug "setLevel(${l}, ${duration}) -> device level ${value}, ${secs} s"
    if (value > 0) LEVEL_SET_AT.put(deviceKey(), now()) else LEVEL_SET_AT.remove(deviceKey())
    parent?.componentSetLevel(device, value, secs)
}

void startLevelChange(direction) {
    parent?.componentStartLevelChange(device, direction)
}

void stopLevelChange() {
    parent?.componentStopLevelChange(device)
}

// With a turn-on level set in preferences, changes it: every turn-on uses the new level until the next presetLevel.
// Without one, sets the level for the next turn-on only (on() or a button press); setLevel in between does not use it
// up. Either way it does not turn the light on; if the dimmer is on, it also sets the level now.
// Firmware 1.08 or later applies it from parameter 9; older firmware ignores parameter 9, so only on() uses it there.
void presetLevel(level) {
    Integer l = clamp(toInt(level) ?: 0, 1, 100)
    Integer onLevel = deviceLevel(l)
    boolean persistent = fixedOnLevel() != null
    String text = "${device.displayName} level was preset to ${l}%"
    logInfo "level preset to ${l}%${persistent ? " (turn-on level)" : " (next turn-on)"}"
    sendEvent(name: "levelPreset", value: l, unit: "%", descriptionText: text)

    if (persistent) {
        device.updateSetting("onLevel", [value: onLevel.toString(), type: "number"])
        if (turnOnLevelSupported()) {
            // Written straight away, and recorded as the desired value so the sync status stays right.
            parent?.componentSendParameter(device, 9, 1, 128L + onLevel)
            parent?.componentSetParameters(device, [[num: 9, size: 1, value: 128L + onLevel]])
        }
    }

    if (device.currentValue("switch") == "on") {
        setLevel(l)
        return
    }
    sendEvent(name: "level", value: l, unit: "%", type: "digital", descriptionText: text)
    if (!persistent) {
        state.presetLevel = l
        // 1-99 = level for the next turn-on only. Sent every time, not compared with the last value.
        if (turnOnLevelSupported()) parent?.componentSendParameter(device, 9, 1, onLevel as Long)
    }
}

void refresh() {
    parent?.componentRefresh(device)
}

/* ---------- Events from the parent ---------- */

// Child devices receive no Z-Wave frames; the parent sends events through parse(List<Map>) below.
void parse(String description) {
    logDebug "parse(String) is not used: ${description}"
}

void parse(List<Map> events) {
    events.each { Map evt ->
        // A preset is for the next turn-on only: on() removes it itself, and a turn-on by hand uses it up here.
        // A turn-on by setLevel (digital) leaves it for the next on().
        if (evt.name == "switch" && evt.value == "on" && evt.type == "physical" && device.currentValue("switch") != "on") {
            state.remove("presetLevel")
        }
        if (txtEnable && evt.descriptionText && device.currentValue(evt.name)?.toString() != evt.value?.toString()) {
            log.info evt.descriptionText
        }
        sendEvent(evt)
    }
}

/* ---------- Levels ---------- */

// Hubitat level (0-100) -> device level (0-99). The device applies the minimum/maximum levels itself.
private Integer deviceLevel(Integer level) {
    return clamp(level, 0, 99)
}

// Parameter 9 (turn-on level) needs firmware 1.08 or later; 1.05 accepts it but ignores it.
// The parent stores the firmware version on this device.
private boolean turnOnLevelSupported() {
    List<String> parts = (device.getDataValue("firmwareVersion") ?: "").tokenize(".")
    Integer major = parts ? toInt(parts[0]) : null
    Integer minor = parts.size() > 1 ? toInt(parts[1]) : 0
    return major != null && (major > 1 || (major == 1 && (minor ?: 0) >= 8))
}

// On/off duration preference (parameter 3) in seconds, or null when not set: the parent then uses the device's own
// parameter 3.
private Integer onOffSeconds() {
    Integer value = toInt(settings.onOffDuration)
    if (value == null) return null
    value = clamp(value, 0, 255)
    return value <= 127 ? value : (value - 127) * 60
}

/* ---------- Helpers ---------- */

// True shortly after a setLevel above 0, before the device's report has turned the switch attribute on.
private boolean levelJustSet() {
    Long at = LEVEL_SET_AT.get(deviceKey())
    return at != null && now() - at < LEVEL_SET_WINDOW_MS
}

private String deviceKey() {
    return "${device.id}".toString()
}

// 1-99, or null when not set. 0 (allowed by earlier 2.0 builds) counts as not set.
private Integer fixedOnLevel() {
    Integer v = toInt(settings.onLevel)
    return v ? clamp(v, 1, 99) : null
}

private Integer toInt(value) {
    Long v = toLong(value)
    return v != null ? v.intValue() : null
}

private Long toLong(value) {
    if (value == null || value.toString().trim() == "") return null
    try {
        return new BigDecimal(value.toString().trim()).longValue()
    } catch (NumberFormatException ignored) {
        return null
    }
}

private Integer clamp(Integer value, Integer min, Integer max) {
    return Math.max(min, Math.min(max, value))
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${device.displayName}: ${msg}"
}
