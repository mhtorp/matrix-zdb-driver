import groovy.transform.Field

@Field static Map commandClassVersions = [
        0x20: 1,    //basic
        0x26: 4,    //switchMultiLevel
        0x5B: 3,    //centralScene
        0x60: 4,    //multiChannel
        0x25: 1,    //switchBinary
        0x33: 1     //switchColor
]
@Field static Map switchVerbs = [0:"was turned",1:"is"]
@Field static Map levelVerbs = [0:"was set to",1:"is"]
@Field static Map switchValues = [0:"off",1:"on"]

metadata {
    definition (name: "Matrix ZDB 5100", namespace: "mhtorp", author: "Mathias Husted Torp") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"

        command "setDefaultAssociations"
        command "setAssociationGroup", [[name: "Group*", type: "NUMBER", range: "1..14"], [name: "Node*", type: "STRING"], [name: "Action*", type: "ENUM", constraints: [0, 1]], [name: "Endpoint", type: "NUMBER", range: "0..5"]]
        command "processAssociations"

        attribute "numberOfButtons", "number"

        fingerprint deviceId: "0121", inClusters: "0x5E,0x55,0x6C,0x98,0x9F", mfr: "0234", prod: "0003", deviceJoinName: "Matrix ZDB 5100"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "fakeButtonPush", type: "bool", title: "Send button push event on switch toggle", defaultValue: true
    }
}

List<String> installed() {
    if (logEnable) log.debug "installed"
    createChildDevices()
    configure()
}

List<String> updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(5400,logsOff)
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

List<String> configure() {
    if (logEnable) log.debug "configure"
    sendEvent(name: "numberOfButtons", value: 4)

    if (!childDevices) {
        createChildDevices()
    }

    List<String> assoc_cmds = processAssociations()
    List<String> cmds = []
    for(cmd in assoc_cmds) {
        cmds << cmd
    }

    cmds << secure(zwave.versionV1.versionGet())
    cmds << secure(zwave.manufacturerSpecificV2.manufacturerSpecificGet())

    // Fix white level of device LEDs
    cmds << setParameter(parameterNumber = 14, size = 4, value = 4283782400)

    childDevices.each{ child ->
        List<String> config_cmds = child.configure()
        for (cmd in config_cmds) {
            cmds << cmd
        }
    }

    delayBetween(cmds, 100)
}

List<String> refresh() {
    if (logEnable) log.debug "refresh"
    List<String> cmds = []
    cmds << secure(zwave.basicV2.basicGet())
    return cmds
}

void parse(String description){
    if (logEnable) log.debug "parse description: ${description}"
    hubitat.zwave.Command cmd = zwave.parse(description,commandClassVersions)
    if (cmd) {
        zwaveEvent(cmd)
    }
}

void zwaveEvent(hubitat.zwave.Command cmd, endpoint = null) {
    if (logEnable) log.debug "skip: ${cmd}" + (endpoint ? " from endpoint $endpoint" : "")
}

String setParameter(Integer parameterNumber, Integer size, Long value) {
    log.info "setParameter: $parameterNumber, $size, $value"
    String cmd = secure(zwave.configurationV1.configurationSet(
        scaledConfigurationValue: value as Integer,
        parameterNumber: parameterNumber as Integer,
        size: size as Integer
    ))
    return cmd
}

private void createChildDevices() {
    if (logEnable) log.debug "createChildDevices"
    
    for (i in 1..4) {
        addChildDevice("mhtorp", "Matrix ZDB 5100 (Button)", "${device.deviceNetworkId}-$i", [name: "ch$i", label: "$device.displayName Button $i", isComponent: true])
    }
    addChildDevice("mhtorp", "Matrix ZDB 5100 (Dimmer)", "${device.deviceNetworkId}-5", [name: "ch5", label: "$device.displayName Dimmer", isComponent: true])
}

// Parent commands -------------------------------------------------------------------

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd, endpoint = 0) {
    if (logEnable) log.debug "CentralSceneNotification: ${cmd}"

    Integer button = cmd.sceneNumber
    Integer key = cmd.keyAttributes
    String action
    switch (key){
        case 0: //pushed
            action = "pushed"
            break
        case 1: //released, only after 2
            state."${button}" = 0
            action = "released"
            break
        case 2: //holding
            state."${button}" = 1
            action = "held"
            break
        case 3: //double tap
            action = "doubleTapped"
            break
        case 4: //tripple tap
            action = "trippleTapped"
            break
    }

    if (action && !(fakeButtonPush && action == "pushed")) {
        sendButtonEvent(action, button, "physical")
    }
}

// Encapsulation -----------

String secure(String cmd){
    return zwaveSecureEncap(cmd)
}

String secure(hubitat.zwave.Command cmd){
    return zwaveSecureEncap(cmd.format())
}

private encap(hubitat.zwave.Command cmd, Integer endpoint) {
    if (logEnable) log.debug("encap $cmd $endpoint")
    if (endpoint) {
        cmd = zwave.multiChannelV4.multiChannelCmdEncap(destinationEndPoint:endpoint).encapsulate(cmd)
    }
    return cmd.format()
}

void sendCommands(List<String> cmds) {
    for(cmd in cmds) {
        sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
    }
}

private Integer channelNumber(String dni) {
    Integer ch = dni.split("-")[-1] as Integer
    return ch
}

void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    if (logEnable) log.debug "multichannelv4.MultiChannelCmdEncap $cmd"
    def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
    if (logEnable) log.debug "encapsulatedCommand: $encapsulatedCommand"
 
    if (encapsulatedCommand) {
        zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
    } else {
        if (logEnable) log.debug "Unable to get encapsulated command: $encapsulatedCommand"
    }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, endpoint = 0){
    hubitat.zwave.Command encapCmd = cmd.encapsulatedCommand(commandClassVersions)
    if (encapCmd) {
        if (logEnable) log.debug "SupervisionGet: ${encapCmd}, endpoint: ${endpoint}"
        zwaveEvent(encapCmd, endpoint)
    }
    runInMillis(500, sendSupervisionReport, [data:["cmd":cmd, "endpoint":endpoint]])
}

def sendSupervisionReport(Map options) {
    if (logEnable) log.debug("Sending supervisionReport for sessionID: ${options.cmd.sessionID}")
    def report_cmd = zwave.supervisionV1.supervisionReport(
        sessionID: options.cmd.sessionID,
        reserved: 0,
        moreStatusUpdates: false,
        status: 0xFF,
        duration: 0
    )
    if (options.endpoint > 0) {
        report_cmd = encap(report_cmd, options.endpoint)
    }
    sendHubCommand(new hubitat.device.HubAction(secure(report_cmd), hubitat.device.Protocol.ZWAVE))
}

// centralSceneController ----------------------------------------------

void push(button) {
    sendButtonEvent("pushed", button, "digital")
}

void hold(button) {
    sendButtonEvent("held", button, "digital")
}

void release(button) {
    sendButtonEvent("released", button, "digital")
}

void doubleTap(button) {
    sendButtonEvent("doubleTapped", button, "digital")
}

void sendButtonEvent(action, button, type) {
    String descriptionText = "${device.displayName} button ${button} was ${action} [${type}]"
    if (txtEnable) log.info descriptionText
    sendEvent(name:action, value:button, descriptionText:descriptionText, isStateChange:true, type:type)
}


// Switch ------------------------------------------

//// Parsers -----------------

// returns on physical
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, endpoint = 0) {
    if (logEnable) log.debug "basicv1.BasicReport $cmd, $endpoint"
    if (fakeButtonPush) sendButtonEvent("pushed", endpoint, "physical")
    basicEvents(cmd.value, "physical", endpoint)
}

// returns on digital
void zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, endpoint = 0) {
    if (logEnable) log.debug "switchbinaryv1.SwitchBinaryReport $cmd, $endpoint"
    zwaveBinaryEvent(cmd, "digital", endpoint)
}

void zwaveEvent(hubitat.zwave.commands.switchcolorv1.SwitchColorReport cmd, endpoint = 0) {
    if (logEnable) log.debug "switchcolorv1.SwitchColorReport $cmd, $endpoint"
    zwaveColorEvent(cmd, "digital", endpoint)
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    if (logEnable) log.debug "manufacturerspecificv2.ManufacturerSpecificReport cmd $cmd"
    updateDataValue("MSR", String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId))
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd, endpoint) {
   if (logEnable) log.debug "versionv1.VersionReport, applicationVersion $cmd.applicationVersion, cmd $cmd, endpoint $endpoint"
}

//// Commands --------------------------

List<String> childBinaryOn(child) {
    return zwaveBinaryCmd(0xFF, channelNumber(child.deviceNetworkId))
}

List<String> childBinaryOff(child) {
    return zwaveBinaryCmd(0x00, channelNumber(child.deviceNetworkId))
}

List<String> zwaveBinaryCmd(value, int endpoint) {
    List<String> cmds = []
    cmds << secure(encap(zwave.switchBinaryV1.switchBinarySet(switchValue: value), endpoint))
    cmds << zwaveBinaryGet(endpoint)
    return cmds
}

String childBinaryGet(child) {
    return zwaveBinaryGet(channelNumber(child.deviceNetworkId))
}

String zwaveBinaryGet(endpoint) {
    return secure(encap(zwave.switchBinaryV1.switchBinaryGet(), endpoint))
}

List<String> childSetColor(child, colorMap) {
    return setColorCmd(colorMap, channelNumber(child.deviceNetworkId))
}

private List<String> setColorCmd(colorMap, endpoint) {
    if (logEnable) log.debug "setColorCmd, colorMap: $colorMap, endpoint: $endpoint"
    
    List rgb = hubitat.helper.ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    
    def cmds = []
    cmds << secure(encap(zwave.switchColorV1.switchColorSet(red: rgb[0], green: rgb[1], blue: rgb[2], warmWhite: 16), endpoint))
    
    return delayBetween(cmds, 100)
}

List<String> flash(child, rateToFlash, red, green, blue) {
    flashCmd(rateToFlash as Integer, red as Integer, green as Integer, blue as Integer, channelNumber(child.deviceNetworkId) as Integer)
}

private List<String> flashCmd(rateToFlash, red, green, blue, endpoint) {
    if (logEnable) log.debug "flash, red: $red, green: $green, blue: $blue, rateToFlash: $rateToFlash, endpoint: $endpoint"
    def cmds = []
    cmds << secure(encap(zwave.switchColorV1.switchColorSet(red: red, green: green, blue: blue, warmWhite: (128 + 16 + rateToFlash)), endpoint))
    // cmds << secure(encap(zwave.switchColorV1.switchColorSet(warmWhite: (128 + 16 + rateToFlash)), endpoint))
    cmds << secure(encap(zwave.switchColorV1.switchColorGet(colorComponent: "warmWhite"), endpoint))
    
    return delayBetween(cmds, 100)
}


// Dimmer --------------------------------------------------------------

//// Parsers -------------

//returns on physical
void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, endpoint = 0){
    if (logEnable) log.debug "SwitchMultilevelReport value: ${cmd.value}"
    dimmerEvents(cmd.value, "digital", endpoint)
}

//// Commands -------------

private Float scale_between_ranges(Float n, Float s_min, Float s_max, Float t_min, Float t_max) {
    x = (n - s_min) / (s_max - s_min) * (t_max - t_min) + t_min
    return x as Float
}

List<String> childBasicGet(child) {
    Integer endpoint = channelNumber(child.deviceNetworkId)
    state.bin = -2
    List<String> cmds = []
    cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
    return cmds
}

List<String> childSetLevel(child, level, duration) {
    Integer endpoint = channelNumber(child.deviceNetworkId)
    state.bin = level
    List<String> cmds = []
    cmds << secure(encap(zwave.switchMultilevelV4.switchMultilevelSet(value: level, dimmingDuration: duration), endpoint))
    return cmds
}

List<String> childStartLevelChange(child, String direction){
    Integer upDown = direction == "down" ? 1 : 0
    Integer endpoint = channelNumber(child.deviceNetworkId)
    List<String> cmds = []
    cmd = zwave.switchMultilevelV4.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel: 1, startLevel: 0)
    cmds << secure(encap(cmd, endpoint))
    return cmds
}

List<String> childStopLevelChange(child){
    Integer endpoint = channelNumber(child.deviceNetworkId)
    List<String> cmds = []
    cmds << secure(encap(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), endpoint))
    cmds << secure(encap(zwave.basicV1.basicGet(), endpoint))
    return delayBetween(cmds, 200)
}


// Common events ---------------------------------------------

void basicEvents(rawValue, type, endpoint) {
    Integer value = rawValue.toInteger()
    if (endpoint == 5) {
        dimmerEvents(value, "physical", endpoint)
    } else {
        switchEvents(value, "physical", endpoint)
    }
}

void zwaveBinaryEvent(cmd, type, endpoint) {
    if (logEnable) log.debug "zwaveBinaryEvent cmd $cmd, endpoint $endpoint, type $type"
    String value = cmd.value ? "on" : "off"
    if (txtEnable) log.info "${device.displayName} switch ${endpoint} is ${value} [${type}]"
    def childDevice = childDevices.find{it.deviceNetworkId.endsWith("$endpoint")}
    def result = null
 
    if (childDevice) {
        if (logEnable) log.debug "childDevice.sendEvent $cmd.value"
        result = childDevice.sendEvent(name: "switch", value: value, type: type)
    } else {
        result = createEvent(name: "switch", value: value, type: type)
    }
    result
}

void zwaveColorEvent(cmd, String type, Integer endpoint) {
    if (logEnable) log.debug "Unhandled zwaveColorEvent: endpoint $endpoint, type $type"
}

void switchEvents(levelValue, type, endpoint = 0) {
    if (logEnable) log.debug "switchEvents (${endpoint}) levelValue: ${levelValue}, type: ${type}"
    if (endpoint > 0) {
        childDevice = childDevices.find{it.deviceNetworkId.endsWith("$endpoint")}
    }
    Integer crntSwitch
    if (childDevice) {
        crntSwitch = (childDevice.currentValue("switch") == "on") ? 1 : 0
    } else {
        crntSwitch = (device.currentValue("switch") == "on") ? 1 : 0
    }
    if (levelValue == 0){
        switchValue = switchValues[0]
        switchText = "${switchVerbs[crntSwitch ^ 1]} ${switchValue}"// --c1" //xor
    } else {
        switchValue = switchValues[1]
        switchText = "${switchVerbs[crntSwitch & 1]} ${switchValue}"// --c3"
    }
    switchText = "${device.displayName} (${endpoint}) ${switchText} [${type}]"
    if (txtEnable) log.info "${switchText}"
    
    if (childDevice) {
        childDevice.sendEvent(name: "switch", value: switchValue, descriptionText: switchText, type:type)
    } else {
        sendEvent(name: "switch", value: switchValue, descriptionText: switchText, type:type)
    }
}

void dimmerEvents(Integer levelValue, String type, Integer endpoint = 0){
    if (logEnable) log.debug "dimmerEvents levelValue: ${levelValue}, type: ${type}, endpoint: ${endpoint}, state.bin: ${state.bin}"

    def currentDevice
    Integer scaledLevel
    levelValue = (levelValue > 100) ? 100 : levelValue
    if (endpoint > 0) {
        currentDevice = childDevices.find{it.deviceNetworkId.endsWith("$endpoint")}
        scaledLevel = currentDevice.level_scaled_to_raw(levelValue)
    } else {
        currentDevice = device
        scaledLevel = levelValue
    }

    // Integer crntLevel = (currentDevice.currentValue("level") ?: 50).toInteger()
    // Integer crntSwitch = (currentDevice.currentValue("switch") == "on") ? 1 : 0

    // Switch event
    Integer n = levelValue ? 1 : 0
    String switchValue = switchValues[n]
    if (txtEnable) log.info "${currentDevice.displayName} is ${switchValue} [${type}]"
    currentDevice.sendEvent(name: "switch", value: switchValue, type:type)

    // SwitchLevel event
    if (scaledLevel) {
        if (txtEnable) log.info "${currentDevice.displayName} is set to ${scaledLevel} % [${type}]"
        currentDevice.sendEvent(name: "level", value: scaledLevel, type:type, unit:"%")
    }
}


// Association groups -----------------------------------------------

void setDefaultAssociations() {
    if (txtEnable) log.info "setDefaultAssociations()"
    def hubId = (zwaveHubNodeId.toString().format( '%02x', zwaveHubNodeId )).toUpperCase()
    state.defaultG1 = [hubId + "-0"]
    state.defaultG2 = []
    state.defaultG3 = []
}

void setAssociationGroup(group, nodes, action, endpoint = null) {
    if (logEnable) log.debug "setAssociationGroup($group, $nodes, $action, $endpoint)"
    if (endpoint) {
        nodes = nodes + "-" + endpoint
    }
    if (!state."desiredAssociation${group}") {
        state."desiredAssociation${group}" = nodes
    } else {
        switch (action as Integer) {
            case 0:
                if (txtEnable) log.info "Removing '$nodes' from state.desiredAssociation${group}: " + state."desiredAssociation${group}"
                state."desiredAssociation${group}" = state."desiredAssociation${group}" - nodes
            break
            case 1:
                if (txtEnable) log.info "Adding '$nodes' to state.desiredAssociation${group}: " + state."desiredAssociation${group}"
                state."desiredAssociation${group}" = state."desiredAssociation${group}" + nodes
            break
        }
    }
}

List<String> processAssociations() {
    List<String> cmds = []
    setDefaultAssociations()
    int associationGroups = 14
    if (state.associationGroups) {
        associationGroups = state.associationGroups
    } else {
        if (txtEnable) log.info "${device.displayName}: Getting supported association groups from device"
        cmds << secure(zwave.associationV2.associationGroupingsGet())
    }
    for (int i = 1; i <= associationGroups; i++){
        if(state."actualAssociation${i}" != null){
            if(state."desiredAssociation${i}" != null || state."defaultG${i}") {
                boolean refreshGroup = false
                ((state."desiredAssociation${i}"? state."desiredAssociation${i}" : [] + state."defaultG${i}") - state."actualAssociation${i}").each {
                    if (it != null){
                        if (txtEnable) log.info "${device.displayName}: Adding node $it to group $i"
                        if (it.matches("\\p{XDigit}+")) {
                            cmds << secure(zwave.associationV2.associationSet(groupingIdentifier:i, nodeId:Integer.parseInt(it,16)))
                        } else if (it.matches("\\p{XDigit}+-\\p{XDigit}+")) {
                            List<String> endpoint = it.split("-")
                            int nodeId = Integer.parseInt(endpoint[0],16) // Parse as hex
                            int endpointId = Integer.parseInt(endpoint[1],16) // Parse as hex
                            if (logEnable) log.debug "${device.displayName}: $it unpacked to node $nodeId, endpoint $endpointId"
                            cmds << secure(zwave.multiChannelAssociationV2.multiChannelAssociationSet(groupingIdentifier: i, multiChannelNodeIds: [[nodeId: nodeId, bitAddress: 0, endPointId: endpointId]]))
                        }
                        refreshGroup = true
                    }
                }
                ((state."actualAssociation${i}" - state."defaultG${i}") - state."desiredAssociation${i}").each {
                    if (it != null){
                        if (txtEnable) log.info "${device.displayName}: Removing node $it from group $i"
                        if (it.matches("\\p{XDigit}+")) {
                            cmds << secure(zwave.associationV2.associationRemove(groupingIdentifier:i, nodeId:Integer.parseInt(it,16)))
                        } else if (it.matches("\\p{XDigit}+-\\p{XDigit}+")) {
                            List<String> endpoint = it.split("-")
                            int nodeId = Integer.parseInt(endpoint[0],16) // Parse as hex
                            int endpointId = Integer.parseInt(endpoint[1],16) // Parse as hex
                            if (logEnable) log.debug "${device.displayName}: $it unpacked to node $nodeId, endpoint $endpointId"
                            cmds << secure(zwave.multiChannelAssociationV2.multiChannelAssociationRemove(groupingIdentifier: i, multiChannelNodeIds: [[nodeId: nodeId, bitAddress: 0, endPointId: endpointId]]))
                        }
                        refreshGroup = true
                    }
                }
                if (refreshGroup == true) cmds << secure(zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier:i))
                else if (txtEnable) log.info "${device.displayName}: There are no association actions to complete for group $i"
            }
        } else {
            if (txtEnable) log.info "${device.displayName}: Association info not known for group $i. Requesting info from device."
            cmds << secure(zwave.associationV2.associationGet(groupingIdentifier:i))
        }
    }
    if (cmds.size() > 0) {
        cmds = delayBetween(cmds, 200)
    }
    return cmds
}

void zwaveEvent(hubitat.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport cmd) {
    if (logEnable) log.debug "${device.displayName}: ${cmd}"
    List<String> temp = []
    if (cmd.nodeId != []) {
       cmd.nodeId.each {
          temp += it.toString().format( '%02x', it.toInteger() ).toUpperCase()
       }
    } 
    if (cmd.multiChannelNodeIds != []) {
       cmd.multiChannelNodeIds.each {
          temp += it.toString().format( '%02x-%x', it.nodeId.toInteger(), it.endPointId.toInteger() ).toUpperCase()
       }
    } 
    state."actualAssociation${cmd.groupingIdentifier}" = temp
    if (txtEnable) log.info "${device.displayName}: Multi-channel associations for Group ${cmd.groupingIdentifier}: ${temp}"
    updateDataValue("associationGroup${cmd.groupingIdentifier}", "$temp")
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd, endpoint = NULL) {
    if (logEnable) log.debug "${device.displayName} (${endpoint}): ${cmd}"
    List<String> temp = []
    if (cmd.nodeId != []) {
       cmd.nodeId.each {
          temp += it.toString().format( '%02x', it.toInteger() ).toUpperCase()
       }
    } 
    state."actualAssociation${cmd.groupingIdentifier}" = temp
    if (txtEnable) log.info "${device.displayName} (${endpoint}): Associations for Group ${cmd.groupingIdentifier}: ${temp}"
    updateDataValue("associationGroup${cmd.groupingIdentifier}", "$temp")
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    if (logEnable) log.debug "${device.displayName}: ${cmd}"
    sendEvent(name: "groups", value: cmd.supportedGroupings)
    if (txtEnable) log.info "${device.displayName}: Supported association groups: ${cmd.supportedGroupings}"
    state.associationGroups = cmd.supportedGroupings
}

