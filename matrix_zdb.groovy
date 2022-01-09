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
    definition (name: "Matrix ZDB 5100", namespace: "logicgroup", author: "Mathias Husted Torp") {
        capability "Actuator"
        capability "Refresh"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"
        
        command "childOn", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4", "5"]]]
        command "childOff", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4", "5"]]]
        command "childSetLevel", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4", "5"]], [name: "level*", type: "NUMBER", range: "0..99"]]
        command "childGet", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4", "5"]]]
        command "childStartLevelChange", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4", "5"]], [name: "direction*", type: "ENUM", constraints: ["up", "down"]]]
        command "childStopLevelChange", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4", "5"]]]

        command "flash", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4"]], [name: "rateToFlash", type: "NUMBER"], [name: "red", type: "NUMBER", range: "0..255"], [name: "green", type: "NUMBER", range: "0..255"], [name: "blue", type: "NUMBER", range: "0..255"]]
        command "configure"
        command "setColor", [[name: "childDevice*", type: "ENUM", constraints: ["1", "2", "3", "4"]], [name: "color*", type: "COLOR_MAP"]]
        command "recreateChildDevices"
        command "deleteChildren"
        command "setParameter",[[name:"parameterNumber*",type:"NUMBER", description:"Parameter Number", constraints:["NUMBER"]],[name:"size*",type:"NUMBER", description:"Parameter Size", constraints:["NUMBER"]],[name:"value*",type:"NUMBER", description:"Parameter Value", constraints:["NUMBER"]]]
        command "processAssociations"
        command "setAssociationGroup", [[name: "Group*", type: "NUMBER", range: "1..14"], [name: "Node*", type: "STRING"], [name: "Action*", type: "ENUM", constraints: [0, 1]], [name: "Endpoint", type: "NUMBER", range: "0..5"]]
        command "setDefaultAssociations"
        command "singleChannelsRefresh"
        command "singleChannelsSet"
        command "singleChannelsRemove"
        command "refreshMultiChannels"

        attribute "numberOfButtons", "number" //sendEvent(name:"pushed", value:<button number that was pushed>)

        fingerprint deviceId: "0121", inClusters: "0x5E,0x55,0x6C,0x98,0x9F", mfr: "0234", prod: "0003", deviceJoinName: "Matrix ZDB 5100"
   }

   preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() {
    if (logEnable) log.debug "installed"
    createChildDevices()
    configure()
}

def updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(5400,logsOff)

    
    if (!childDevices) {
        createChildDevices()
    }
    configure()
}

def configure() {
    if (logEnable) log.debug "configure"
    sendEvent(name: "numberOfButtons", value: 4)
    def assoc_cmds = processAssociations()
    def cmds = []
    for(cmd in assoc_cmds) {
        cmds << cmd
    }

    cmds << secure(zwave.versionV1.versionGet().format())
    cmds << secure(zwave.manufacturerSpecificV2.manufacturerSpecificGet().format())
    // cmds << secure(zwave.firmwareUpdateMdV2.firmwareMdGet().format())

    // Fix white level of device LEDs
    setParameter(parameterNumber = 14, size = 4, value = 4283782400)

    delayBetween(cmds, 200)
}

def refresh() {
    if (logEnable) log.debug "refresh"
    state.bin = -2
    def cmds = []
    cmds << secure(zwave.basicV2.basicGet().format())
    // cmds << zwave.switchBinaryV1.switchBinaryGet().format()

    (1..5).each { endpoint ->
        cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
        // cmds << encap(zwave.switchBinaryV1.switchBinaryGet(), endpoint)
    }
 
    delayBetween(cmds, 100)
}

def recreateChildDevices() {
    if (logEnable) log.debug "recreateChildDevices"
    deleteChildren()
    createChildDevices()
}

def deleteChildren() {
	if (logEnable) log.debug "deleteChildren"
	def children = getChildDevices()
    
    children.each {child->
  		deleteChildDevice(child.deviceNetworkId)
    }
}


def parse(String description) {
    if (logEnable) log.debug "parse $description"
    def result = null
 
    if (description.startsWith("Err")) {
        result = createEvent(descriptionText:description, isStateChange:true)
    } else {
        def cmd = zwave.parse(description, commandClassVersions)
        if (logEnable) log.debug "Command: ${cmd}"
  
        if (cmd) {
            result = zwaveEvent(cmd)
            if (logEnable) log.debug "parsed '${description}' to ${result.inspect()}"
        } else {
            if (logEnable) log.debug "Unparsed description $description"
        }
    }
 
    result
}


def zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    if (logEnable) log.debug "multichannelv4.MultiChannelCmdEncap $cmd"
    if (logEnable) log.debug "${cmd.getProperties()}"
    def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
    if (logEnable) log.debug "encapsulatedCommand: $encapsulatedCommand"
 
    if (encapsulatedCommand) {
        return zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
    } else {
        if (logEnable) log.debug "Unable to get encapsulated command: $encapsulatedCommand"
        return []
    }
}

//returns on physical
void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, endpoint = null){
    if (logEnable) log.debug "SwitchMultilevelReport value: ${cmd.value}"
    dimmerEvents(cmd.value,"physical", endpoint)
}

//returns on digital
def zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, endpoint = null) {
    if (logEnable) log.debug "basicv2.BasicReport $cmd, $endpoint"
    dimmerEvents(cmd.value,"digital", endpoint)
}

//returns on digital
def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, endpoint = null) {
    if (logEnable) log.debug "basicv1.BasicReport $cmd, $endpoint"
    dimmerEvents(cmd.value,"digital", endpoint)
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

String secure(String cmd){
    return zwaveSecureEncap(cmd)
}

String secure(hubitat.zwave.Command cmd){
    return zwaveSecureEncap(cmd)
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, endpoint = 0){
    hubitat.zwave.Command encapCmd = cmd.encapsulatedCommand(commandClassVersions)
    if (encapCmd) {
        if (logEnable) log.debug "SupervisionGet: ${encapCmd}, endpoint: ${endpoint}"
        zwaveEvent(encapCmd, endpoint)
    }   
    runInMillis(500, sendSupervisionReport, [data:["cmd":cmd, "endpoint":endpoint]])
}

def sendSupervisionReport(options) {
    def cmd = options["cmd"]
    def endpoint = options["endpoint"]
    if (logEnable) log.debug("Sending supervisionReport: $cmd")
    def report_cmd = zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0)
    if (endpoint > 0) {
        report_cmd = encap(report_cmd, endpoint)
    } else {
        report_cmd = report_cmd.format()
    }
    sendHubCommand(new hubitat.device.HubAction(secure(report_cmd), hubitat.device.Protocol.ZWAVE))
}

def zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd, endpoint = null) {
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
            //if (state."${button}" == 0){
                state."${button}" = 1
                runInMillis(200,delayHold,[data:button])
            //}
            break
        case 3: //double tap, 4 is tripple tap
            action = "doubleTapped"
            break
        case 4: //double tap, 4 is tripple tap
            action = "trippleTapped"
            break
    }

    if (action){
        sendButtonEvent(action, button, "physical")
    }
}

def zwaveEvent(hubitat.zwave.commands.switchcolorv2.SwitchColorReport cmd, endpoint = null) {
    if (logEnable) log.debug "switchcolorv2.SwitchColorReport $cmd, $endpoint"
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, endpoint = null) {
    if (logEnable) log.debug "switchbinaryv1.SwitchBinaryReport $cmd, $endpoint"
    zwaveBinaryEvent(cmd, endpoint, "physical")
}

def zwaveBinaryEvent(cmd, endpoint, type) {
    if (logEnable) log.debug "zwaveBinaryEvent cmd $cmd, endpoint $endpoint, type $type"
    def childDevice = childDevices.find{it.deviceNetworkId.endsWith("$endpoint")}
    def result = null
    String value = cmd.value ? "on" : "off"
 
    if (childDevice) {
        if (logEnable) log.debug "childDevice.sendEvent $cmd.value"
        result = childDevice.sendEvent(name: "switch", value: value, type: type)
    } else {
        result = createEvent(name: "switch", value: value, type: type)
    }
    result
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    if (logEnable) log.debug "manufacturerspecificv2.ManufacturerSpecificReport cmd $cmd"
    updateDataValue("MSR", String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId))
}

def zwaveEvent(hubitat.zwave.commands.configurationv2.ConfigurationReport cmd) {
    if (logEnable) log.debug "configurationv2.ConfigurationReport: parameter ${cmd.parameterNumber} with a byte size of ${cmd.size} is set to ${cmd.configurationValue}"
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    if (logEnable) log.debug "configurationv2.ConfigurationReport: parameter ${cmd.parameterNumber} with a byte size of ${cmd.size} is set to ${cmd.configurationValue}"
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd, endpoint) {
   if (logEnable) log.debug "versionv1.VersionReport, applicationVersion $cmd.applicationVersion, cmd $cmd, endpoint $endpoint"
}

def zwaveEvent(hubitat.zwave.Command cmd, endpoint) {
    if (logEnable) log.debug "${device.displayName}: Unhandled ${cmd}" + (endpoint ? " from endpoint $endpoint" : "")
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) log.debug "${device.displayName}: Unhandled ${cmd} with class '${cmd.class}'"
}

def childStartLevelChange(String dni, String direction){
    Integer upDown = direction == "down" ? 1 : 0
    def cmds = []
    def cmd = zwave.switchMultilevelV1.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel: 1, startLevel: 0)
    cmds << secure(encap(cmd, channelNumber(dni)))
    return cmds
}

def childStopLevelChange(String dni){
    return [
            secure(encap(zwave.switchMultilevelV1.switchMultilevelStopLevelChange(), channelNumber(dni)))
            ,"delay 200"
            ,secure(encap(zwave.basicV1.basicGet(), channelNumber(dni)))
    ]
}

def on() {
    if (logEnable) log.debug "on"
    def cmds = []
    cmds << secure(zwave.basicV2.basicSet(value: 0xFF).format())
    cmds << secure(zwave.basicV2.basicGet().format())
    
    (1..5).each { endpoint ->
        cmds << secure(encap(zwave.basicV2.basicSet(value: 0xFF), endpoint))
        cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
    }

    return delayBetween(cmds, 100)
}

def off() {
    if (logEnable) log.debug "off"
    def cmds = []
    cmds << secure(zwave.basicV2.basicSet(value: 0x00).format())
    cmds << secure(zwave.basicV2.basicGet().format())
    
    (1..5).each { endpoint ->
        cmds << secure(encap(zwave.basicV2.basicSet(value: 0x00), endpoint))
        cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
    }
    
    return delayBetween(cmds, 100)
}

def childOn(String dni) {
    state.bin = -11
    onOffCmd(0xFF, channelNumber(dni))
}

def childOff(String dni) {
    state.bin = -10
    onOffCmd(0, channelNumber(dni))
}

def childSetLevel(String dni, level, duration = 0) {
    if (logEnable) log.debug "childSetLevel $dni, $level, $duration"
    if (level > 99) level = 99
    state.bin = level
    setLevelCmd(level as Integer, duration as Integer, channelNumber(dni))
}

def childGet(String dni) {
    def endpoint = channelNumber(dni)
    def cmds = []
    cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
    return cmds
}

private setLevelCmd(value, duration, endpoint) {
    if (logEnable) log.debug "setLevelCmd, value: $value, duration: $duration, endpoint: $endpoint"
    
    def cmds = []
    cmds << secure(encap(zwave.basicV2.basicSet(value: value), endpoint))
    // cmds << secure(encap(zwave.switchMultilevelV3.switchMultilevelSet(value: value, dimmingDuration: duration), endpoint))
    cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
    if (logEnable) log.debug "setLevelCmd cmds: $cmds"
    return delayBetween(cmds, 1100)
}

private onOffCmd(value, endpoint) {
    if (logEnable) log.debug "onOffCmd, value: $value, endpoint: $endpoint"
    
    def cmds = []
    cmds << secure(encap(zwave.basicV2.basicSet(value: value), endpoint))
    cmds << secure(encap(zwave.basicV2.basicGet(), endpoint))
    if (logEnable) log.debug "onOffCmd cmds: $cmds"
    return delayBetween(cmds, 1000)
}

private channelNumber(String dni) {
    def ch = dni.split("-")[-1] as Integer
    return ch
}

def setColor(String dni, colorMap) {
    setColorCmd(colorMap, channelNumber(dni))
}

private setColorCmd(colorMap, endpoint) {
    if (logEnable) log.debug "setColorCmd, colorMap: $colorMap, endpoint: $endpoint"
    
    List rgb = hubitat.helper.ColorUtils.hsvToRGB([colorMap.hue, colorMap.saturation, colorMap.level])
    
    def cmds = []
    cmds << secure(encap(zwave.switchColorV2.switchColorSet(red: rgb[0], green: rgb[1], blue: rgb[2], warmWhite: 16), endpoint))
    cmds << secure(encap(zwave.switchColorV2.switchColorGet(colorComponent: "red"), endpoint))
    
    return delayBetween(cmds, 100)
}

def flash(String dni, rateToFlash, red, green, blue) {
    flashCmd(rateToFlash, red, green, blue, channelNumber(dni))
}

private flashCmd(rateToFlash, red, green, blue, endpoint) {
    if (logEnable) log.debug "flash, rateToFlash: $rateToFlash, endpoint: $endpoint"
    def cmds = []
    cmds << secure(encap(zwave.switchColorV2.switchColorSet(red: red, green: green, blue: blue, warmWhite: (128 + 16 + rateToFlash)), endpoint))
    // cmds << secure(encap(zwave.switchColorV2.switchColorSet(warmWhite: (128 + 16 + rateToFlash)), endpoint))
    cmds << secure(encap(zwave.switchColorV2.switchColorGet(colorComponent: "warmWhite"), endpoint))
    
    return delayBetween(cmds, 100)
}

private void createChildDevices() {
    if (logEnable) log.debug "createChildDevices"
    
    for (i in 1..4) {
        addChildDevice("logicgroup", "Matrix ZDB 5100 (Button)", "$device.deviceNetworkId-$i", [name: "ch$i", label: "$device.displayName Button $i", isComponent: true])
    }
    addChildDevice("logicgroup", "Matrix ZDB 5100 (Dimmer)", "$device.deviceNetworkId-5", [name: "ch5", label: "$device.displayName Dimmer", isComponent: true])
}

private encap(cmd, endpoint) {
    if (logEnable) log.debug("encap $cmd $endpoint")
    if (endpoint) {
        cmd = zwave.multiChannelV4.multiChannelCmdEncap(destinationEndPoint:endpoint).encapsulate(cmd)
    }
    cmd.format()
}

void delayHold(button) {
    sendButtonEvent("held", button, "physical")
}

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

List<String> setParameter(parameterNumber = null, size = null, value = null) {
    if (parameterNumber == null || size == null || value == null) {
        log.warn "incomplete parameter list supplied..."
        log.info "syntax: setParameter(parameterNumber,size,value)"
    } else {
        parameterNumber = parameterNumber as Integer
        size = size as Integer
        value = value as Integer
        log.info "setParameter: $parameterNumber, $size, $value"
        return delayBetween([
            secure(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: parameterNumber, size: size)),
            secure(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber))
        ], 100)
    }
}

Integer rgbwToInt(red, green, blue, white) {
    return (
        ((red   & 0xFF) << 24) | 
        ((green & 0xFF) << 16) | 
        ((blue  & 0xFF) << 8 ) | 
        ((white & 0xFF) << 0 )
    )
}

void dimmerEvents(rawValue, type, endpoint = null){
    if (logEnable) log.debug "dimmerEvents value: ${rawValue}, type: ${type}, endpoint: ${endpoint}, state.bin: ${state.bin}"

    def childDevice = null
    if (endpoint) {
        childDevice = childDevices.find{it.deviceNetworkId.endsWith("$endpoint")}
    }

    Integer levelValue = rawValue.toInteger()
    Integer crntLevel = (device.currentValue("level") ?: 50).toInteger()
    Integer crntSwitch = (device.currentValue("switch") == "on") ? 1 : 0

    String switchText
    String levelText
    String levelVerb
    String switchValue

    switch(state.bin) {
        case -1:
            if (levelValue == 0){
                switchValue = switchValues[0]
                switchText = "${switchVerbs[crntSwitch ^ 1]} ${switchValue}"// --c1" //xor
            } else {
                switchValue = switchValues[1]
                switchText = "${switchVerbs[crntSwitch & 1]} ${switchValue}"// --c3"
                if (levelValue == crntLevel) levelText = "${levelVerbs[1]} ${crntLevel}%"// --c3a"
                else levelText = "${levelVerbs[0]} ${levelValue}%"// --c3b"
            }
            break
        case 0..100: //digital set level -basic report
            switchValue = switchValues[levelValue ? 1 : 0]
            switchText = "${switchVerbs[crntSwitch & 1]} ${switchValue}"// --c4"
            if (levelValue == 0) levelValue = 1
            levelVerb = levelVerbs[levelValue == crntLevel ? 1 : 0]
            levelText = "${verb} ${levelValue}%"// --c4"
            break
        case -11: //digital on -basic report
            switchValue = switchValues[1]
            switchText = "${switchVerbs[crntSwitch & 1]} ${switchValue}"// --c5"
            break
        case -10: //digital off -basic report
            switchValue = switchValues[0]
            switchText = "${switchVerbs[crntSwitch ^ 1]} ${switchValue}"// --c6"
            break
        case -2: //refresh digital -basic report
            if (levelValue == 0){
                switchValue = switchValues[0]
                switchText = "${switchVerbs[1]} ${switchValue}"// --c10"
                levelText = "${levelVerbs[1]} ${crntLevel}%"// --c10"
                levelValue = crntLevel
            } else {
                switchValue = switchValues[1]
                switchText = "${switchVerbs[1]} ${switchValue}"// --c11"
                levelText = "${levelVerbs[1]} ${levelValue}%"// --c11"
            }
            break
        default :
            log.debug "missing- bin: ${state.bin}, levelValue:${levelValue}, crntLevel: ${crntLevel}, crntSwitch: ${crntSwitch}, type: ${type}"
            break
    }

    if (switchText){
        switchText = "${device.displayName} (${endpoint}) ${switchText} [${type}]"
        if (txtEnable) log.info "${switchText}"
        if (childDevice) {
            childDevice.sendEvent(name: "switch", value: switchValue, descriptionText: switchText, type:type)
        } else {
            sendEvent(name: "switch", value: switchValue, descriptionText: switchText, type:type)
        }
    }
    if (levelText){
        levelText = "${device.displayName} (${endpoint}) ${levelText} [${type}]"
        if (txtEnable) log.info "${levelText}"
        if (childDevice) {
            childDevice.sendEvent(name: "level", value: levelValue, descriptionText: levelText, type:type,unit:"%")
        } else {
            sendEvent(name: "level", value: levelValue, descriptionText: levelText, type:type,unit:"%")
        }
    }
    state.bin = -1
}

// Association groups
def setDefaultAssociations() {
    if (txtEnable) log.info "setDefaultAssociations()"
    def smartThingsHubID = (zwaveHubNodeId.toString().format( '%02x', zwaveHubNodeId )).toUpperCase()
    state.defaultG1 = [smartThingsHubID + "-0"]
    state.defaultG2 = []
    state.defaultG3 = []
}

def setAssociationGroup(group, nodes, action, endpoint = null){
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

def processAssociations(){
    def cmds = []
    setDefaultAssociations()
    def associationGroups = 14
    if (state.associationGroups) {
        associationGroups = state.associationGroups
    } else {
        if (txtEnable) log.info "${device.displayName}: Getting supported association groups from device"
        cmds << secure(zwave.associationV2.associationGroupingsGet())
    }
    for (int i = 1; i <= associationGroups; i++){
        if(state."actualAssociation${i}" != null){
            if(state."desiredAssociation${i}" != null || state."defaultG${i}") {
                def refreshGroup = false
                ((state."desiredAssociation${i}"? state."desiredAssociation${i}" : [] + state."defaultG${i}") - state."actualAssociation${i}").each {
                    if (it != null){
                        if (txtEnable) log.info "${device.displayName}: Adding node $it to group $i"
                        if (it.matches("\\p{XDigit}+")) {
                            cmds << secure(zwave.associationV2.associationSet(groupingIdentifier:i, nodeId:Integer.parseInt(it,16)))
                        } else if (it.matches("\\p{XDigit}+-\\p{XDigit}+")) {
                            def endpoint = it.split("-")
                            def nodeId = Integer.parseInt(endpoint[0],16) // Parse as hex
                            def endpointId = Integer.parseInt(endpoint[1],16) // Parse as hex
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
                            def endpoint = it.split("-")
                            def nodeId = Integer.parseInt(endpoint[0],16) // Parse as hex
                            def endpointId = Integer.parseInt(endpoint[1],16) // Parse as hex
                            if (logEnable) log.debug "${device.displayName}: $it unpacked to node $nodeId, endpoint $endpointId"
                            cmds << secure(zwave.multiChannelAssociationV2.multiChannelAssociationRemove(groupingIdentifier: i, multiChannelNodeIds: [[nodeId: nodeId, bitAddress: 0, endPointId: endpointId]]))
                        }
                        refreshGroup = true
                    }
                }
                // if (refreshGroup == true) cmds << secure(zwave.associationV2.associationGet(groupingIdentifier:i))
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

def zwaveEvent(hubitat.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport cmd) {
    if (logEnable) log.debug "${device.displayName}: ${cmd}"
    def temp = []
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

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd, endpoint = NULL) {
    if (logEnable) log.debug "${device.displayName} (${endpoint}): ${cmd}"
    def temp = []
    if (cmd.nodeId != []) {
       cmd.nodeId.each {
          temp += it.toString().format( '%02x', it.toInteger() ).toUpperCase()
       }
    } 
    state."actualAssociation${cmd.groupingIdentifier}" = temp
    if (txtEnable) log.info "${device.displayName} (${endpoint}): Associations for Group ${cmd.groupingIdentifier}: ${temp}"
    updateDataValue("associationGroup${cmd.groupingIdentifier}", "$temp")
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    if (logEnable) log.debug "${device.displayName}: ${cmd}"
    sendEvent(name: "groups", value: cmd.supportedGroupings)
    if (txtEnable) log.info "${device.displayName}: Supported association groups: ${cmd.supportedGroupings}"
    state.associationGroups = cmd.supportedGroupings
}

def singleChannelsSet(){
    if (txtEnable) log.info "${device.displayName}: singleChannelsSet"
    def cmds = []
    for (int i = 1; i <= 5; i++) {
        cmds << secure(encap(zwave.associationV2.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId), i))
    }
    return cmds
}

def singleChannelsRemove() {
    if (txtEnable) log.info "${device.displayName}: singleChannelsRemove"
    def cmds = []
    for (int i = 1; i <= 5; i++) {
        cmds << secure(encap(zwave.associationV2.associationRemove(groupingIdentifier:1, nodeId:zwaveHubNodeId), i))
    }
    return cmds
}



def refreshMultiChannels() {
    associationGroups = state.associationGroups
    def cmds = []
    cmds << secure(zwave.multiChannelAssociationV2.multiChannelAssociationRemove(groupingIdentifier: 1, multiChannelNodeIds: [[nodeId: zwaveHubNodeId, bitAddress: 0, endPointId: 0]]))
    for (int i = 0; i <= 5; i++) {
        cmds << secure(encap(zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier:1), i))
    }
    return cmds
}

def singleChannelsRefresh() {
    if (txtEnable) log.info "${device.displayName}: singleChannelsRefresh"
    associationGroups = state.associationGroups
    def cmds = []
    for (int i = 0; i <= 5; i++) {
        cmds << secure(encap(zwave.associationV2.associationGet(groupingIdentifier:1), i))
    }
    return cmds
}
