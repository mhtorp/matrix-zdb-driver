/*
 *  LogicGroup Matrix ZDB Button Endpoint Driver
 */
metadata {
    definition (name: "Matrix ZDB 5100 (Button)", namespace: "mhtorp", author: "Mathias Husted Torp") {
        capability "ColorControl"
        capability "Configuration"
        capability "Flash"
        capability "Refresh"
        capability "Switch"

        command "setColorOn",  [[name: "red*", type: "NUMBER", range:"0..255"], [name: "green*", type: "NUMBER", range:"0..255"], [name: "blue*", type: "NUMBER", range:"0..255"]]
        command "setColorOff", [[name: "red*", type: "NUMBER", range:"0..255"], [name: "green*", type: "NUMBER", range:"0..255"], [name: "blue*", type: "NUMBER", range:"0..255"]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "p16", type: "enum", title: "16. Button operation mode", options:[[0:"Default operation (Toggle on/off and dim/brighten)"],[1:"Turn off timer"],[2:"Turn on timer"],[3:"Always turn off or dim"],[4:"Always turn on or brighten"]], defaultValue: 0
        input name: "p17", type: "number", title: "17. Timer duration in seconds for parameter 16", range:"0..43200", defaultValue: 300
        input name: "p19", type: "enum", title: "19. Binary Switch Set behaviour", options: [[0: "Only control LED"], [1: "Control switch status and LED"], [2: "As if physical switch activated, including sending commands to association groups"]], defaultValue: 0
        input name: "p20", type: "enum", title: "20. Button LED mode", options:[[0:"Only control by external commands"],[1:"Sync with button status"],[2:"Sync with inverted button status"],[5:"Sync with dimmer status (on/off)"],[6:"Sync with inverted dimmer status (on/off)"],[7:"Turn on for 5 seconds upon push of button"]], defaultValue: 7
    }
}

void configure() {
    updated()
}

void updated() {
    // ch1: 16-23
    // ch2: 24-31
    // ch3: 32-39
    // ch4: 40-47
    def ch = device.deviceNetworkId.split("-")[-1] as Integer
    state.button = ch 
    Integer offset = (ch - 1) * 8
    cmds = []
    cmds << parent.setParameter(parameterNumber = 16 + offset, size = 1, value = p16.toInteger())
    cmds << parent.setParameter(parameterNumber = 17 + offset, size = 2, value = p17.toInteger())
    cmds << parent.setParameter(parameterNumber = 19 + offset, size = 1, value = p19.toInteger())
    cmds << parent.setParameter(parameterNumber = 20 + offset, size = 1, value = p20.toInteger())
    cmds << parent.setParameter(parameterNumber = 21 + offset, size = 1, value = 0)
    parent.sendCommands(cmds)
}

void on() {
    if (logEnable) log.debug "${device.displayName} on"
    List<String> cmds = parent.childBinaryOn(device)
    parent.sendCommands(cmds)
}

void off() {
    if (logEnable) log.debug "${device.displayName} off"
    List<String> cmds = parent.childBinaryOff(device)
    parent.sendCommands(cmds)
}

void refresh() {
    if (logEnable) log.debug "${device.displayName} refresh"
    List<String> cmds = parent.childBinaryGet(device)
    parent.sendCommands(cmds)
}

void setColor(colorMap) {
    if (logEnable) log.debug "${device.displayName} setColor: Color map: $colorMap"
    List rgb = hubitat.helper.ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    state.red = rgb[0]
    state.green = rgb[1]
    state.blue = rgb[2]
    List<String> cmds = parent.childSetColor(device, colorMap)
    parent.sendCommands(cmds)
}

void flash(rateToFlash) {
    List<String> cmds = parent.flash(device, rateToFlash, state.red, state.green, state.blue)
    parent.sendCommands(cmds)
}


List<String> setColorParam(Integer param, Integer red, Integer green, Integer blue) {
    def ch = state.button as Integer
    param = param + (ch - 1) * 8
    Integer colorInt = rgbwToInt(red as Integer, green as Integer, blue as Integer, 0)
    List<String> cmds = []
    cmds << parent.setParameter(parameterNumber = param, size = 4, value = colorInt)
    return cmds
}

void setColorOn(red, green, blue) {
    if (txtEnable) log.info "setColorOn (${state.button}: Color map: [$red, $green, $blue]"
    List<String> cmds = setColorParam(22, red as Integer, green as Integer, blue as Integer)
    parent.sendCommands(cmds)
}

void setColorOff(red, green, blue) {
    if (txtEnable) log.info "setColorOff (${state.button}: Color map: [$red, $green, $blue]"
    List<String> cmds = setColorParam(23, red as Integer, green as Integer, blue as Integer)
    parent.sendCommands(cmds)
}

Integer rgbwToInt(Integer red, Integer green, Integer blue, Integer white) {
    return (
        ((red   & 0xFF) << 24) | 
        ((green & 0xFF) << 16) | 
        ((blue  & 0xFF) << 8 ) | 
        ((white & 0xFF) << 0 )
    )
}
