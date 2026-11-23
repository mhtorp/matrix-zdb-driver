/**
 *  Matrix ZDB 5100 (Button) — child driver for one button (endpoints 1-4) of the Logic Group MATRIX ZDB5100
 *
 *  Created by the "Matrix ZDB 5100" parent driver. The switch state is the button's on/off state; the colour
 *  commands control the button's RGB LED. Button events (pushed, held, ...) are on the parent device.
 *
 *  Source and documentation: https://github.com/mhtorp/matrix-zdb-driver
 */

import groovy.transform.Field

// Each button has 8 parameters: button 1 uses 16-23, button 2 24-31, button 3 32-39, button 4 40-47.
// offset: position within the button's block.
@Field static final List<Map> BUTTON_PARAMS = [
    [offset: 0, name: "buttonMode", size: 1, title: "Button function",
     options: [0: "Toggle on/off, hold to dim", 1: "Turn off after timer (staircase light)", 2: "Turn on after timer",
               3: "Always off / dim down (for button pairs)", 4: "Always on / dim up (for button pairs)"],
     help: "Device default: Toggle."],
    [offset: 1, name: "timerDuration", size: 2, title: "Timer (seconds)", range: "0..43200",
     help: "Used by the timer functions. Device default 300."],
    [offset: 3, name: "binarySwitchMode", size: 1, title: "On/off commands from the hub control",
     options: [0: "The LED only", 1: "The button state and LED", 2: "Everything, as a physical press (also sends to associated devices)"],
     help: "Device default: The LED only."],
    [offset: 4, name: "ledMode", size: 1, title: "LED indication",
     options: [0: "Only by colour commands", 1: "Follow button state", 2: "Follow button state (inverted)",
               5: "Follow dimmer on/off", 6: "Follow dimmer on/off (inverted)", 7: "On for 5 seconds when pressed"],
     help: "Device default: On for 5 seconds when pressed."],
    [offset: 5, name: "colorCommandMode", size: 1, title: "Colour commands set",
     options: [0: "The LED directly, until the next press", 1: "The off-state colour", 2: "The on-state colour"],
     help: "Ignored while Enhanced LED control is enabled on the parent device. Device default: The off-state colour."],
    [offset: 6, name: "onColor", size: 4, color: true, title: "LED colour in on state (RRGGBB)",
     help: "Device default 00007F (blue)."],
    [offset: 7, name: "offColor", size: 4, color: true, title: "LED colour in off state (RRGGBB)",
     help: "Device default 2F2F2F (dim white)."]
]

metadata {
    definition(name: "Matrix ZDB 5100 (Button)", namespace: "mhtorp", author: "Mathias Husted Torp",
               importUrl: "https://raw.githubusercontent.com/mhtorp/matrix-zdb-driver/master/matrix_zdb_button.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "ColorControl"
        capability "Flash"
        capability "Refresh"
        capability "Configuration"

        command "setColorOn",  [[name: "red*", type: "NUMBER", range: "0..255"], [name: "green*", type: "NUMBER", range: "0..255"], [name: "blue*", type: "NUMBER", range: "0..255"]]
        command "setColorOff", [[name: "red*", type: "NUMBER", range: "0..255"], [name: "green*", type: "NUMBER", range: "0..255"], [name: "blue*", type: "NUMBER", range: "0..255"]]
    }

    preferences {
        BUTTON_PARAMS.each { p ->
            Map opts = [name: p.name, title: p.title, description: p.help]
            if (p.options) {
                input(opts + [type: "enum", options: p.options])
            } else if (p.color) {
                input(opts + [type: "text"])
            } else {
                input(opts + [type: "number", range: p.range])
            }
        }
        input "group4Enabled", "enum", title: "Single press sends a level to associated dimmers (group 4)",
            options: [1: "Yes", 0: "No"], description: "Device default: Yes."
        input "group4OnLevel", "number", title: "Level sent when turning on", range: "0..255",
            description: "0-99, or 255 for the dimmer's last level. Device default 255."
        input "group4OffLevel", "number", title: "Level sent when turning off", range: "0..99",
            description: "Device default 0."
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
    parent?.componentSetParameters(device, desiredParameters())
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// Also called by the parent during its configure(); must not call back into the parent.
List<Map> desiredParameters() {
    Integer base = baseParameter()
    List<Map> result = []
    BUTTON_PARAMS.each { p ->
        def raw = settings[p.name]
        Long value = p.color ? colorToParam(raw?.toString()) : toLong(raw)
        result << [num: base + p.offset, size: p.size, value: value] // null = no longer managed
    }

    // Group 4 (Multilevel Switch Set) settings share one 4-byte parameter: enabled, on level, off level, 0.
    if ([settings.group4Enabled, settings.group4OnLevel, settings.group4OffLevel].any { it != null && it.toString() != "" }) {
        Long enabled = orDefault(toLong(settings.group4Enabled), 1L)
        Long onLevel = orDefault(toLong(settings.group4OnLevel), 255L)
        Long offLevel = Math.min(orDefault(toLong(settings.group4OffLevel), 0L), 99L)
        if (onLevel > 99 && onLevel != 255) onLevel = 99
        result << [num: base + 2, size: 4, value: (enabled << 24) | (onLevel << 16) | (offLevel << 8)]
    } else {
        result << [num: base + 2, size: 4, value: null]
    }
    logDebug "desired parameters: ${result}"
    return result
}

/* ---------- Commands ---------- */

void on() {
    logDebug "on()"
    parent?.componentOn(device)
}

void off() {
    logDebug "off()"
    parent?.componentOff(device)
}

void refresh() {
    parent?.componentRefresh(device)
}

void setColor(Map colorMap) {
    logDebug "setColor(${colorMap})"
    parent?.componentSetColor(device, colorMap)
}

void setHue(hue) {
    parent?.componentSetHue(device, hue)
}

void setSaturation(saturation) {
    parent?.componentSetSaturation(device, saturation)
}

void flash(rateToFlash = null) {
    parent?.componentFlash(device, rateToFlash)
}

void setColorOn(red, green, blue) {
    setStateColor("onColor", 6, red, green, blue)
}

void setColorOff(red, green, blue) {
    setStateColor("offColor", 7, red, green, blue)
}

/* ---------- Events from the parent ---------- */

// Child devices receive no Z-Wave frames; the parent sends events through parse(List<Map>) below.
void parse(String description) {
    logDebug "parse(String) is not used: ${description}"
}

void parse(List<Map> events) {
    events.each { Map evt ->
        if (txtEnable && evt.descriptionText && device.currentValue(evt.name)?.toString() != evt.value?.toString()) {
            log.info evt.descriptionText
        }
        sendEvent(evt)
    }
}

/* ---------- Helpers ---------- */

// Stores the colour as the preference too, so the parent's parameter sync does not undo it. Written straight away,
// so a command sent right after this one (e.g. off() to show the new off-state colour) arrives after it.
private void setStateColor(String settingName, Integer offset, red, green, blue) {
    List<Integer> rgb = [red, green, blue].collect { Math.max(0, Math.min(255, toLong(it)?.intValue() ?: 0)) }
    String hex = String.format("%02X%02X%02X", rgb[0], rgb[1], rgb[2])
    logInfo "${settingName == 'onColor' ? 'on' : 'off'}-state LED colour set to ${hex}"
    device.updateSetting(settingName, [value: hex, type: "text"])
    parent?.componentWriteParameters(device, [[num: baseParameter() + offset, size: 4, value: colorToParam(hex)]])
}

private Integer buttonNumber() {
    return device.deviceNetworkId.split("-")[-1] as Integer
}

private Integer baseParameter() {
    return 16 + (buttonNumber() - 1) * 8
}

private Long colorToParam(String hex) {
    String h = hex?.trim()?.replaceFirst(/^#/, "")
    if (!h) return null
    if (!(h ==~ /[0-9A-Fa-f]{6}/)) {
        log.warn "${device.displayName}: '${hex}' is not a colour in RRGGBB format"
        return null
    }
    return Long.parseLong(h, 16) << 8
}

private Long orDefault(Long value, Long fallback) {
    return value != null ? value : fallback
}

private Long toLong(value) {
    if (value == null || value.toString().trim() == "") return null
    try {
        return new BigDecimal(value.toString().trim()).longValue()
    } catch (NumberFormatException ignored) {
        return null
    }
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${device.displayName}: ${msg}"
}
