/*
 *  LogicGroup Matrix ZDB Button Endpoint Driver
 */
metadata {
    definition (name: "Matrix ZDB 5100 (Button)", namespace: "logicgroup", author: "Mathias Husted Torp") {
        capability "Actuator"
        capability "Bulb"
        capability "ColorControl"
        capability "Flash"
        capability "Switch"

        command "configure"
        command "setColorParam", [[name: "mode*", type: "ENUM", constraints: ["Set ON color", "Set OFF color"]], [name: "red*", type: "NUMBER", range:"0..255"], [name: "green*", type: "NUMBER", range:"0..255"], [name: "blue*", type: "NUMBER", range:"0..255"]]
        command "setAssociationGroup"
        command "setLevel"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "p16", type: "enum", title: "16. Button operation mode", options:[[0:"Default operation (Toggle on/off and dim/brighten)"],[1:"Turn off timer"],[2:"Turn on timer"],[3:"Always turn off or dim"],[4:"Always turn on or brighten"]], defaultValue: 0
        input name: "p17", type: "number", title: "17. Timer duration for parameter 16", range:"0..43200", defaultValue: 300
        input name: "p19", type: "enum", title: "19. Binary Switch Set behaviour", options: [[0: "Only control LED"], [1: "Control switch status and LED"], [2: "As if physical switch activated, including sending commands to association groups"]], defaultValue: 0
        input name: "p20", type: "enum", title: "20. Button LED mode", options:[[0:"Only control by external commands"],[1:"Sync with button status"],[2:"Sync with inverted button status"],[5:"Sync with dimmer status (on/off)"],[6:"Sync with inverted dimmer status (on/off)"],[7:"Turn on for 5 seconds upon push of button"]], defaultValue: 7
    }
}

def configure() {
    // ch1: 16-23
    // ch2: 24-31
    // ch3: 32-39
    // ch4: 40-47
    def ch = device.deviceNetworkId.split("-")[-1] as Integer
    state.button = ch 
    Integer offset = (ch - 1) * 8
    parent.setParameter(parameterNumber = 16 + offset, size = 1, value = p16.toInteger())
    parent.setParameter(parameterNumber = 17 + offset, size = 2, value = p17.toInteger())
    parent.setParameter(parameterNumber = 19 + offset, size = 1, value = p19.toInteger())
    parent.setParameter(parameterNumber = 20 + offset, size = 1, value = p20.toInteger())
    parent.setParameter(parameterNumber = 21 + offset, size = 1, value = 0)
}

def updated() {
    configure()
}

void on() { 
    if (logEnable) log.debug "$device on"
    parent.sendButtonEvent("pushed", state.button, "digital")
    parent.childOn(device.deviceNetworkId)
}

void off() {
    if (logEnable) log.debug "$device off"
    parent.sendButtonEvent("pushed", state.button, "digital")
    parent.childOff(device.deviceNetworkId)
}

void setColor(colorMap) {
    if (txtEnable) log.info "setColor: Color map: $colorMap"
    List rgb = hubitat.helper.ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    state.red = rgb[0]
    state.green = rgb[1]
    state.blue = rgb[2]
    parent.setColor(device.deviceNetworkId, colorMap)
}

void setLevel(level, duration = 0) {
    parent.childSetLevel(device.deviceNetworkId, level, duration)
}

void setColorParam(String mode, red, green, blue) {
    if (txtEnable) log.info "setColorParam: Mode: $mode, Color map: [$red, $green, $blue]"
    Integer param = 0
    switch (mode){
        case "Set ON color":
            param = 22
            break
        case "Set OFF color":
            param = 23
            break
    }
    if (param != 0) {
        if (logEnable) log.debug "Setting param $param"
        def ch = device.deviceNetworkId.split("-")[-1] as Integer
        param = param + (ch - 1) * 8
        Integer colorInt = rgbwToInt(red.intValueExact(), green.intValueExact(), blue.intValueExact(), 16)
        parent.setParameter(parameterNumber = param, size = 4, value = colorInt)
    }
}

void flash(rateToFlash) {
    parent.flash(device.deviceNetworkId, rateToFlash, state.red, state.green, state.blue)
}

Integer rgbwToInt(red, green, blue, white) {
    return (
        ((red   & 0xFF) << 24) | 
        ((green & 0xFF) << 16) | 
        ((blue  & 0xFF) << 8 ) | 
        ((white & 0xFF) << 0 )
    )
}
