/*
 *  LogicGroup Matrix ZDB Dimmer Endpoint Driver
 */
metadata {
    definition (name: "Matrix ZDB 5100 (Dimmer)", namespace: "mhtorp", author: "Mathias Husted Torp") {
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "LevelPreset"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "prestaging", type: "bool", title: "Enable level pre-staging", defaultValue: true
        input name: "prestage_button", type: "number", title: "Button to use for level pre-staging", range: "1..4", defaultValue: 1
        input name: "p1", type: "number", title: "1. Pushbutton(s) for dimmer", range: "0..15",
            description: "0: None, 1: Button 1, 2: Button 2, 4: Button 3, 8: Button 4. Add the numbers to select multiple buttons"
        input name: "p2", type: "number", title: "2. Duration of dimming", range:"0..255", defaultValue: 5
        input name: "p3", type: "number", title: "3. Duration of on/off", range:"0..255", defaultValue: 0
        input name: "p4", type: "enum",   title: "4. Dimming mode", options:[[0:"No dimming"], [1:"Trailing Edge - TE"], [2:"Leading Edge - LE"]], defaultValue: 1
        input name: "p5", type: "number", title: "5. Dimmer min level", range: "0..100", defaultValue: 0
        input name: "p6", type: "number", title: "6. Dimmer max level", range: "1..100", defaultValue: 100
        input name: "p18", type: "number", title: "18. Dimmer on level (0 to remember last state)", range: "0..99", defaultValue: 0
        input name: "p26", type: "number", title: "26. Dimmer on level (0 to remember last state)", range: "0..99", defaultValue: 0
        input name: "p34", type: "number", title: "34. Dimmer on level (0 to remember last state)", range: "0..99", defaultValue: 0
        input name: "p42", type: "number", title: "42. Dimmer on level (0 to remember last   state)", range: "0..99", defaultValue: 0
    }
}

def configure() {
    updated()
}

def updated() {
    List<String> cmds = []
    cmds << parent.setParameter(parameterNumber = 1, size = 1, value = p1)
    cmds << parent.setParameter(parameterNumber = 2, size = 1, value = p2)
    cmds << parent.setParameter(parameterNumber = 3, size = 1, value = p3)
    cmds << parent.setParameter(parameterNumber = 4, size = 1, value = p4 as Long)
    min_dim = p5 > 99 ? 99 : p5
    max_dim = p6 > 99 ? 99 : p6
    cmds << parent.setParameter(parameterNumber = 5, size = 1, value = min_dim)
    cmds << parent.setParameter(parameterNumber = 6, size = 1, value = max_dim)
    if (!prestaging) {
        cmds << parent.setParameter(parameterNumber = (10 + prestage_button * 8) as Integer, size = 4, value = get_dimmer_on_value(p6))
    }
    parent.sendCommands(delayBetween(cmds, 100))
}

def get_dimmer_on_value(Long on_level) {
    on_level = on_level != 0 ? on_level : 255
    return (
        ((1 & 0xFF) << 24) |
        ((on_level & 0xFF) << 16) |
        ((0 & 0xFF) << 8 ) |
        ((0 & 0xFF) << 0 )
    )
}

private Integer scale_between_ranges(Float n, Float s_min, Float s_max, Float t_min, Float t_max) {
    Float x = (n - s_min) / (s_max - s_min) * (t_max - t_min) + t_min
    return x.round() as Integer
}

Integer level_raw_to_scaled(Integer level) {
    level = level > 100 ? 100 : level
    level = level < 0 ? 0 : level
    return scale_between_ranges(level as Float, s_min = 0, s_max = 100, t_min = p5, t_max = p6)
}

Integer level_scaled_to_raw(Integer level) {
    lower = p5
    upper = p6
    level = level < lower ? lower : level
    level = level > upper ? upper : level
    Integer scaledLevel = scale_between_ranges(level as Float, s_min = lower, s_max = upper, t_min = 0, t_max = 100)
    if (logEnable) log.debug("level_scaled_to_raw: Scaled $level to $scaledLevel")
    return scaledLevel
}

void setLevel(level, duration = p3) {
    Integer scaledLevel = level_raw_to_scaled(level as Integer)
    if (logEnable) log.debug "$device setLevel $level ($scaledLevel) $duration"
    List<String> cmds = parent.childSetLevel(device, scaledLevel, duration)
    parent.sendCommands(cmds)
}

void presetLevel(level) {
    if (!prestaging) {
        log.warn("Prestaging is not enabled")
    } else {
        if (device.currentValue("switch").toString() == "on") {
            setLevel(level)
        } else {
            sendEvent(name: "level", value: level, descriptionText: levelText, type: "digital", unit: "%")
        }
        Integer scaledLevel = level_raw_to_scaled(level as Integer)
        if (txtEnable) log.info("${device.displayName} Pre-staging button $prestage_button to $level %")
        if (logEnable) log.debug("${device.displayName} Pre-stage button $prestage_button: $level ($scaledLevel) %")
        levelText = "${device.displayName} (${endpoint}) pre-staged to $level %"
        state.prestaged_level = level
        Integer parameterNumber = 10 + prestage_button * 8
        List<String> cmds = []
        cmds << parent.setParameter(parameterNumber = parameterNumber, size = 4, value = get_dimmer_on_value(scaledLevel))
        parent.sendCommands(cmds)
    }
}

void on() {
    if (logEnable) log.debug "$device on"
    Integer level = device.currentValue("level") > 0 ? device.currentValue("level") : 255
    Integer scaledLevel = level_raw_to_scaled(level as Integer)
    List<String> cmds = parent.childSetLevel(device, level = scaledLevel, duration = p3.toInteger())
    parent.sendCommands(cmds)
}

void off() {
    if (logEnable) log.debug "$device off"
    List<String> cmds = parent.childSetLevel(device, level = 0, duration = p3.toInteger())
    parent.sendCommands(cmds)
}

void startLevelChange(direction) {
    List<String> cmds = parent.childStartLevelChange(device, direction, p2.toInteger())
    parent.sendCommands(cmds)
}

void stopLevelChange() {
    List<String> cmds = parent.childStopLevelChange(device)
    parent.sendCommands(cmds)
}

void refresh() {
    List<String> cmds = parent.childBasicGet(device)
    parent.sendCommands(cmds)
}
