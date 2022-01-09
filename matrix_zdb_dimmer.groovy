/*
 *  LogicGroup Matrix ZDB Dimmer Endpoint Driver
 */
metadata {
    definition (name: "Matrix ZDB 5100 (Dimmer)", namespace: "logicgroup", author: "Mathias Husted Torp") {
        capability "Switch"
        capability "SwitchLevel"
        capability "Actuator"
        capability "ChangeLevel"

        command "configure"
        command "refresh"
        command "setAssociationGroup"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
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
    parent.setParameter(parameterNumber = 1, size = 1, value = p1.toInteger())
    parent.setParameter(parameterNumber = 2, size = 1, value = p2.toInteger())
    parent.setParameter(parameterNumber = 3, size = 1, value = p3.toInteger())
    parent.setParameter(parameterNumber = 4, size = 1, value = p4.toInteger())
    min_dim = min_dim > 99 ? 99 : min_dim
    max_dim = max_dim > 99 ? 99 : max_dim
    parent.setParameter(parameterNumber = 5, size = 1, value = min_dim.toInteger())
    parent.setParameter(parameterNumber = 6, size = 1, value = max_dim.toInteger())
    if (!prestaging) {
        parent.setParameter(parameterNumber = 10 + prestage_button * 8, size = 4, value = get_dimmer_on_value(255))
    }
}

def updated() {
    configure()
}

def get_dimmer_on_value(on_level) {
    on_level = on_level != 0 ? on_level : 255
    return (
        ((1   & 0xFF) << 24) |
        ((on_level & 0xFF) << 16) |
        ((0  & 0xFF) << 8 ) |
        ((0 & 0xFF) << 0 )
    )
}

def normalize_level(level) {
    level = level > 100 ? 100 : level
    level = level < 0 ? 0 : level
    upper = p6
    lower = p5
    x = level / 100 * (upper - lower) + lower
    return x as Integer
}

void setLevel(level, duration = 0) {
    if (!prestaging || (device.currentValue("switch").toString() == "on")) {
        if (logEnable) log.debug "$device setLevel $level $duration"
        parent.childSetLevel(device.deviceNetworkId, normalize_level(level), duration)
    }
    def levelText = "${device.displayName} (${endpoint}) set to $level %"
    if (prestaging) {
        if (logEnable) log.debug "$device prestaging $level on $prestage_button"
        levelText = "${device.displayName} (${endpoint}) pre-staged to $level %"
        parent.setParameter(parameterNumber = 10 + prestage_button * 8, size = 4, value = get_dimmer_on_value(normalize_level(level)))
    }
    sendEvent(name: "level", value: level, descriptionText: levelText, type:"digital",unit:"%")
}

void on() {
    if (logEnable) log.debug "$device on"
    def level = device.currentValue("level") > 0 ? device.currentValue("level") : 255
    parent.childSetLevel(device.deviceNetworkId, level = level, duration = p3.toInteger())
}

void off() {
    if (logEnable) log.debug "$device off"
    parent.childOff(device.deviceNetworkId)
}

void startLevelChange(direction) {
    parent.childStartLevelChange(device.deviceNetworkId, direction)
}

void stopLevelChange() {
    parent.childStopLevelChange(device.deviceNetworkId)
}

void refresh() {
    parent.childGet(device.deviceNetworkId)
}

// Association groups
def setDefaultAssociations() {
    if (logEnable) log.debug "$device setDefaultAssociations"
    def smartThingsHubID = (zwaveHubNodeId.toString().format( '%02x', zwaveHubNodeId )).toUpperCase()
    state.defaultG1 = [smartThingsHubID]
    state.defaultG2 = []
    state.defaultG3 = []
}

def setAssociationGroup(group, nodes, action, endpoint = null){
    if (logEnable) log.debug "$device setAssociationGroup $group, $nodes, $acion, $endpoint"
    if (!state."desiredAssociation${group}") {
        state."desiredAssociation${group}" = nodes
    } else {
        switch (action) {
            case 0:
                state."desiredAssociation${group}" = state."desiredAssociation${group}" - nodes
            break
            case 1:
                state."desiredAssociation${group}" = state."desiredAssociation${group}" + nodes
            break
        }
    }
}

def processAssociations() {
    if (logEnable) log.debug "$device processAssociations"
    parent.processAssociations()
}
