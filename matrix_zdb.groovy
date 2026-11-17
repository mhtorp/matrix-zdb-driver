/**
 *  Matrix ZDB 5100 — parent driver for Hubitat
 *
 *  Logic Group MATRIX ZDB5100: a Z-Wave wall switch with four push buttons with RGB LEDs and a built-in dimmer.
 *  This driver handles the root device: button events, device-wide parameters and associations. It creates one
 *  child device per button (endpoints 1-4, driver "Matrix ZDB 5100 (Button)") and one for the dimmer
 *  (endpoint 5, driver "Matrix ZDB 5100 (Dimmer)").
 *
 *  References: MATRIX ZDB5100 user manual (firmware 1.08) and the Z-Wave JS device file 0x0234/zdb5100.json.
 *  Source and documentation: https://github.com/mhtorp/matrix-zdb-driver
 *
 *  Children talk to this driver through Hubitat's component API (componentOn, componentSetLevel, ...), and this
 *  driver sends them events through child.parse(List<Map>). The built-in "Generic Component Switch" and
 *  "Generic Component Dimmer" drivers therefore also work as children, without the per-button settings.
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String VERSION = "2.0.0"

@Field static final Map CMD_CLASS_VERS = [
    0x20: 1, // Basic
    0x25: 1, // Switch Binary
    0x26: 4, // Switch Multilevel
    0x33: 1, // Switch Color
    0x5B: 3, // Central Scene
    0x60: 4, // Multi Channel
    0x6C: 1, // Supervision
    0x70: 1, // Configuration
    0x72: 2, // Manufacturer Specific
    0x85: 2, // Association
    0x86: 1, // Version
    0x8E: 2  // Multi Channel Association
]

@Field static final List<Integer> BUTTON_EPS = [1, 2, 3, 4]
@Field static final Integer DIMMER_EP = 5
@Field static final Integer LAST_PARAM = 47

// Device-wide parameters. Button and dimmer parameters are set on the child devices.
// Option maps are written inline: a @Field initializer cannot reference another @Field.
// defaultValue: written by the driver when the preference is empty. Everything else is only written once you set it.
@Field static final List<Map> PARAMS = [
    [num: 7,  size: 1, title: "Central Scene notifications", options: [0: "Disabled", 1: "Enabled"],
     help: "Device default: Enabled. Needed for held, released and double-tap events."],
    [num: 8,  size: 1, title: "Double-click sets the dimmer to 100%", options: [0: "Disabled", 1: "Enabled"],
     help: "Device default: Enabled."],
    [num: 10, size: 1, title: "Enhanced LED control", options: [0: "Disabled", 1: "Enabled"], defaultValue: "1",
     help: "Needed by the setColor and flash commands on the buttons. Driver default: Enabled."],
    [num: 11, size: 1, title: "Button debounce time (x10 ms)", range: "0..255",
     help: "Device default 5 (50 ms)."],
    [num: 12, size: 1, title: "Button press threshold (x10 ms)", range: "0..255",
     help: "How long a press must be stable to count. Device default 20 (200 ms)."],
    [num: 13, size: 1, title: "Button held threshold (x10 ms)", range: "0..255",
     help: "How long a press lasts before it counts as held. Device default 50 (500 ms)."],
    [num: 14, size: 4, title: "LED colour balance (RRGGBB)", color: true, defaultValue: "FF5555",
     help: "Overall red, green and blue LED intensity for all buttons. Device default FFFFFF; the driver default FF5555 gives a more neutral white."],
    [num: 15, size: 2, title: "Send to association groups securely (bitmask)", range: "0..8191",
     help: "Bit 0 = group 2 ... bit 12 = group 14. Only applies when included securely. Device default 8191 (all)."]
]

// Per-device transient state, kept out of `state` so parse() and child commands do not overwrite each other.
@Field static final Map<String, Long> DIGITAL_UNTIL = new ConcurrentHashMap<>()
@Field static final Map<String, Long> PUSH_SUPPRESSED_UNTIL = new ConcurrentHashMap<>()
@Field static final Map<String, Long> REPORT_PUSHED_AT = new ConcurrentHashMap<>()
@Field static final Map<String, List> LAST_SCENE = new ConcurrentHashMap<>()
@Field static final Map<String, List> LAST_SUPERVISION = new ConcurrentHashMap<>()

metadata {
    definition(name: "Matrix ZDB 5100", namespace: "mhtorp", author: "Mathias Husted Torp",
               importUrl: "https://raw.githubusercontent.com/mhtorp/matrix-zdb-driver/master/matrix_zdb.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"

        command "refreshParameters"
        command "refreshAssociations"
        command "setAssociationGroup", [
            [name: "Group*", type: "NUMBER", description: "Association group 2-14 (see README)"],
            [name: "Nodes", type: "STRING", description: "Comma-separated node IDs in hex, NODE-EP for an endpoint (e.g. 1A, 2B-5). Empty clears the group."]
        ]
        command "repairLifeline"
        command "removeDuplicateChildren"

        attribute "syncStatus", "string"

        fingerprint mfr: "0234", prod: "0003", deviceId: "0121", inClusters: "0x5E,0x55,0x6C,0x98,0x9F", deviceJoinName: "Matrix ZDB 5100"
        // The manual lists product ID 0x0321; Z-Wave JS and production units use 0x0121.
        fingerprint mfr: "0234", prod: "0003", deviceId: "0321", inClusters: "0x5E,0x55,0x6C,0x98,0x9F", deviceJoinName: "Matrix ZDB 5100"
    }

    preferences {
        PARAMS.each { p ->
            Map opts = [name: "configParam${p.num}", title: "${p.num}. ${p.title}", description: p.help]
            if (p.defaultValue != null) opts.defaultValue = p.defaultValue
            if (p.options) {
                input(opts + [type: "enum", options: p.options])
            } else if (p.color) {
                input(opts + [type: "text"])
            } else {
                input(opts + [type: "number", range: p.range])
            }
        }
        input "fakeButtonPush", "bool", title: "Send pushed events from switch reports",
            description: "Used when the device does not send Central Scene for single presses. The driver checks this on the first press.",
            defaultValue: true
        input "logEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
        input "txtEnable", "bool", title: "Enable description text logging", defaultValue: true
    }
}

/* ---------- Lifecycle ---------- */

void installed() {
    logInfo "installed"
    initialize()
    runIn(5, "configure")
}

void updated() {
    logInfo "preferences saved"
    if (logEnable) runIn(1800, "logsOff") else unschedule("logsOff")
    migrateSettings()
    initialize()
    storeDesired(deviceParameters())
    syncParameters()
}

void initialize() {
    sendEvent(name: "numberOfButtons", value: BUTTON_EPS.size())
    updateDataValue("driverVersion", VERSION)
}

// Sends its own commands so it also works when scheduled from installed().
void configure() {
    logInfo "configure"
    if (logEnable) runIn(1800, "logsOff")
    migrateSettings()
    cleanupLegacyState()
    initialize()
    createChildDevices()
    warnDuplicateChildren()
    state.remove("singlePressScenes") // detected again on the next press, in case the firmware changed
    String fw = device.getDataValue("firmwareVersion")
    if (fw) shareFirmwareVersion(fw)

    state.remove("deviceConfig") // forget cached values so every desired parameter is written and read back
    storeDesired(deviceParameters())
    primaryChildren().each { cd ->
        try {
            storeDesired(cd.desiredParameters() as List<Map>)
        } catch (Exception ignored) {
            logDebug "${cd.displayName} has no parameters to apply (generic component driver?)"
        }
    }

    List cmds = [zwave.versionV1.versionGet(), zwave.manufacturerSpecificV2.manufacturerSpecificGet()]
    cmds.addAll(associationGetCommands())
    cmds.addAll(syncCommands())
    cmds.addAll(refreshCommands())
    sendCommands(cmds)
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* ---------- Commands ---------- */

void refresh() {
    logDebug "refresh()"
    sendCommands(refreshCommands())
}

void refreshParameters() {
    logInfo "reading all parameters from the device"
    sendCommands((1..LAST_PARAM).collect { zwave.configurationV1.configurationGet(parameterNumber: it) })
}

void refreshAssociations() {
    logInfo "reading association groups"
    sendCommands(associationGetCommands())
}

// Replaces the members of an association group. Group 1 (lifeline) is managed by the hub.
void setAssociationGroup(group, String nodes = "") {
    Integer g = toInt(group)
    if (g == null || g < 2 || g > 14) {
        log.warn "${device.displayName}: association group must be 2-14, got ${group}"
        return
    }

    List<String> entries = (nodes ?: "").split(/[,;\s]+/).findAll { it }
    if (entries.size() > 5) {
        log.warn "${device.displayName}: group ${g} holds at most 5 members, got ${entries.size()}"
        return
    }
    List<Integer> plain = []
    List<Map> multi = []
    for (String entry : entries) {
        if (!(entry ==~ /[0-9A-Fa-f]{1,2}([-:]\d{1,3})?/)) {
            log.warn "${device.displayName}: invalid association member '${entry}' (expected hex node ID, optionally NODE-EP)"
            return
        }
        List<String> parts = entry.split(/[-:]/) as List
        Integer node = Integer.parseInt(parts[0], 16)
        if (parts.size() > 1) {
            multi << [nodeId: node, bitAddress: 0, endPointId: parts[1] as Integer]
        } else {
            plain << node
        }
    }

    logInfo "association group ${g}: ${entries ? entries.join(', ') : 'cleared'}"
    List cmds = [String.format("8E04%02X", g)] // Multi Channel Association Remove without members = clear the group
    if (plain) cmds << zwave.associationV2.associationSet(groupingIdentifier: g, nodeId: plain)
    if (multi) cmds << zwave.multiChannelAssociationV2.multiChannelAssociationSet(groupingIdentifier: g, multiChannelNodeIds: multi)
    cmds << zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier: g)
    sendCommands(cmds)
}

// Endpoint reports only reach the driver as endpoint reports if the hub is in the lifeline as multi-channel endpoint 0.
void repairLifeline() {
    logInfo "setting the lifeline (group 1) to a multi-channel association with the hub"
    sendCommands([
        zwave.associationV2.associationRemove(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]),
        zwave.multiChannelAssociationV2.multiChannelAssociationSet(groupingIdentifier: 1, multiChannelNodeIds: [[nodeId: zwaveHubNodeId, bitAddress: 0, endPointId: 0]]),
        zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier: 1)
    ])
}

// Deletes all but the oldest child of each endpoint (see childrenFor). Children with apps or rules are normally the
// oldest; check the warning from Configure, which lists the children this removes.
void removeDuplicateChildren() {
    List extra = duplicateChildren()
    if (!extra) {
        logInfo "no duplicate children"
        return
    }
    extra.each { cd ->
        log.warn "${device.displayName}: removing duplicate child ${cd.displayName} (${cd.deviceNetworkId})"
        deleteChildDevice(cd.deviceNetworkId)
    }
}

void push(button)      { buttonEvent(toInt(button), "pushed", "digital") }
void hold(button)      { buttonEvent(toInt(button), "held", "digital") }
void release(button)   { buttonEvent(toInt(button), "released", "digital") }
void doubleTap(button) { buttonEvent(toInt(button), "doubleTapped", "digital") }

void syncParameters() {
    sendCommands(syncCommands())
}

void pollDimmer() {
    sendCommands([endpointGet(DIMMER_EP)])
}

/* ---------- Component API (called by child devices) ---------- */

// transitionTime (dimmer only): seconds, or null for the device's on/off duration (parameter 3).
void componentOn(cd, transitionTime = null) {
    Integer ep = endpointOf(cd)
    logDebug "componentOn(${ep}, ${transitionTime})"
    if (ep == DIMMER_EP) {
        dimmerSet(0xFF, durationSeconds(transitionTime)) // 0xFF: restore the last level (or the level from parameter 9)
    } else {
        buttonSwitch(ep, 0xFF)
    }
}

void componentOff(cd, transitionTime = null) {
    Integer ep = endpointOf(cd)
    logDebug "componentOff(${ep}, ${transitionTime})"
    if (ep == DIMMER_EP) {
        dimmerSet(0, durationSeconds(transitionTime))
    } else {
        buttonSwitch(ep, 0)
    }
}

// Without a transitionTime, a level change uses the on/off duration (parameter 3), as on and off do.
void componentSetLevel(cd, level, transitionTime = null) {
    if (!isDimmer(cd, "setLevel")) return
    Integer value = clamp(toInt(level) ?: 0, 0, 99) // the device's maximum is 99
    Integer secs = durationSeconds(transitionTime)
    if (secs == null) secs = decodeDuration(paramValue(3) ?: 0)
    logDebug "componentSetLevel(${value}, ${secs})"
    dimmerSet(value, secs)
}

void componentStartLevelChange(cd, direction) {
    if (!isDimmer(cd, "startLevelChange")) return
    logDebug "componentStartLevelChange(${direction})"
    Integer dimDuration = paramValue(2)
    markDimmerDigital(decodeDuration(dimDuration != null ? dimDuration : 5))
    // dimmingDuration 0xFF = the device's dimming duration (parameter 2); incDec 3 = no secondary switch.
    sendCommands([endpointCommand(DIMMER_EP, zwave.switchMultilevelV4.switchMultilevelStartLevelChange(
        upDown: direction == "down", ignoreStartLevel: true, startLevel: 0, dimmingDuration: 0xFF, incDec: 3, stepSize: 0))])
}

void componentStopLevelChange(cd) {
    if (!isDimmer(cd, "stopLevelChange")) return
    logDebug "componentStopLevelChange()"
    markDimmerDigital(0)
    sendCommands([endpointCommand(DIMMER_EP, zwave.switchMultilevelV4.switchMultilevelStopLevelChange())])
    schedulePoll()
}

void componentRefresh(cd) {
    sendCommands([endpointGet(endpointOf(cd))])
}

void componentSetColor(cd, Map colorMap) {
    Integer ep = endpointOf(cd)
    if (!(ep in BUTTON_EPS)) return
    Integer hue = clamp(toInt(colorMap?.hue) ?: 0, 0, 100)
    Integer saturation = clamp(toInt(colorMap?.saturation) ?: 0, 0, 100)
    Integer level = colorMap?.level != null ? clamp(toInt(colorMap.level) ?: 0, 0, 100) : 100
    List<Integer> rgb = hsvToRgb(hue, saturation, level)
    logDebug "componentSetColor(${ep}, ${colorMap}) -> RGB ${rgb}"

    // With enhanced LED control, warm white = 0x10 means "direct control until the next button press".
    sendCommands([endpointCommand(ep, colorSet(rgb, 0x10))])

    String hex = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2])
    sendChildEvents(cd, [
        [name: "hue", value: hue, descriptionText: "${cd.displayName} hue was set to ${hue}"],
        [name: "saturation", value: saturation, unit: "%", descriptionText: "${cd.displayName} saturation was set to ${saturation}%"],
        [name: "color", value: hex, descriptionText: "${cd.displayName} LED colour was set to ${hex}"]
    ])
}

void componentSetHue(cd, hue) {
    List<Integer> hsv = currentHsv(cd)
    componentSetColor(cd, [hue: hue, saturation: hsv[1], level: hsv[2]])
}

void componentSetSaturation(cd, saturation) {
    List<Integer> hsv = currentHsv(cd)
    componentSetColor(cd, [hue: hsv[0], saturation: saturation, level: hsv[2]])
}

// Flashes the LED in its current colour. rateToFlash is the on (and off) time in ms, 100-1500.
void componentFlash(cd, rateToFlash = null) {
    Integer ep = endpointOf(cd)
    if (!(ep in BUTTON_EPS)) return
    Integer halfPeriod = clamp(Math.round((toInt(rateToFlash) ?: 750) / 100.0) as Integer, 1, 15)
    List<Integer> rgb = hexToRgb(cd.currentValue("color")?.toString()) ?: [255, 255, 255]
    logDebug "componentFlash(${ep}, ${rateToFlash}) -> RGB ${rgb}, ${halfPeriod * 100} ms"
    // Warm white: bit 7 = blink, bit 4 = direct control, bits 0-3 = half period in 0.1 s.
    sendCommands([endpointCommand(ep, colorSet(rgb, 0x80 | 0x10 | halfPeriod))])
}

// Children pass their desired parameters as [num:, size:, value:] maps. Values are unsigned; null = not managed.
void componentSetParameters(cd, List<Map> params) {
    logDebug "componentSetParameters(${cd.displayName}, ${params})"
    storeDesired(params)
    runIn(2, "syncParameters") // debounce several children saving at once
}

// Writes a parameter once, regardless of the cached value, without making it a desired setting.
// Used by the dimmer to write its turn-on level (parameter 9) straight away, or to clear it.
void componentSendParameter(cd, Integer num, Integer size, Long value) {
    logDebug "componentSendParameter(${cd.displayName}, ${num}, ${size}, ${value})"
    sendCommands([
        zwave.configurationV1.configurationSet(parameterNumber: num, size: size, scaledConfigurationValue: toSigned(value, size)),
        zwave.configurationV1.configurationGet(parameterNumber: num)
    ])
}

/* ---------- Parsing ---------- */

void parse(String description) {
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) {
        zwaveEvent(cmd, 0)
    } else {
        logDebug "Could not parse: ${description}"
    }
}

void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd, Integer ep) {
    hubitat.zwave.Command encap = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (encap) {
        zwaveEvent(encap, cmd.sourceEndPoint as Integer)
    } else {
        logDebug "Could not extract command from ${cmd}"
    }
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, Integer ep) {
    hubitat.zwave.Command encap = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    logDebug "Supervision Get (session ${cmd.sessionID}${ep ? ", endpoint ${ep}" : ""}): ${encap}"
    // The device repeats a Supervision Get if our report is lost; answer again but process it once.
    if (encap && !isRetransmission(LAST_SUPERVISION, "${ep}:${cmd.sessionID}")) zwaveEvent(encap, ep)
    def report = zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0)
    sendCommands([ep ? endpointCommand(ep, report) : report])
}

// Lifeline report from a button (endpoints 1-4) or the dimmer (endpoint 5).
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, Integer ep) {
    logDebug "Endpoint ${ep}: ${cmd}"
    if (ep == DIMMER_EP) {
        dimmerEvents(cmd.value as Integer)
    } else if (ep in BUTTON_EPS) {
        String type = eventType(ep)
        Integer value = cmd.value as Integer
        switchEvent(ep, value, type)
        if (reportPushesEnabled() && type == "physical" && !pushSuppressed(ep) && !isTimerReport(ep, value)) {
            REPORT_PUSHED_AT.put(deviceKey(ep), now())
            buttonEvent(ep, "pushed", "physical")
        }
    }
}

void zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, Integer ep) {
    logDebug "Endpoint ${ep}: ${cmd}"
    if (ep in BUTTON_EPS) switchEvent(ep, cmd.value as Integer, eventType(ep))
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, Integer ep) {
    logDebug "Endpoint ${ep}: ${cmd}"
    if (ep != DIMMER_EP) return
    // During a transition the value is the starting level; use the target, which v4 reports include.
    Integer target = cmd.targetValue as Integer
    Integer duration = cmd.duration as Integer
    boolean moving = target != null && target != 0xFE && duration != null && duration > 0
    dimmerEvents(moving ? target : cmd.value as Integer)
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd, Integer ep) {
    logDebug "${cmd}"
    Integer button = cmd.sceneNumber as Integer
    Integer key = cmd.keyAttributes as Integer
    if (isRetransmission(LAST_SCENE, "${cmd.sequenceNumber}")) return
    endDigital(button)

    switch (key) {
        case 0: // pressed once; 1.05 and 1.09 send it, but Z-Wave JS notes firmware that does not
            if (!state.singlePressScenes) state.singlePressScenes = true
            // The switch report arrives first; skip the push if it already gave one (the first press after detection).
            Long reported = REPORT_PUSHED_AT.remove(deviceKey(button))
            if (reported == null || now() - reported > 2000L) buttonEvent(button, "pushed", "physical")
            break
        case 1: // released
            PUSH_SUPPRESSED_UNTIL.put(deviceKey(button), now() + 2000L)
            buttonEvent(button, "released", "physical")
            break
        case 2: // held; repeated while the button is held down
            // Suppress pushes while held (capped at 30 s in case the release is lost); only the first repeat is an event.
            Long previous = PUSH_SUPPRESSED_UNTIL.put(deviceKey(button), now() + 30000L)
            boolean alreadyHeld = previous != null && previous > now() + 2000L
            if (!alreadyHeld) buttonEvent(button, "held", "physical")
            break
        case 3:
            buttonEvent(button, "doubleTapped", "physical")
            break
        default:
            logDebug "Button ${button} pressed ${key - 1} times (no matching Hubitat button event)"
    }
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd, Integer ep) {
    Long value = unsignedValue(cmd.configurationValue)
    logDebug "Parameter ${cmd.parameterNumber} (size ${cmd.size}) = ${value}"
    Map cfg = state.deviceConfig ?: [:]
    cfg["${cmd.parameterNumber}".toString()] = value
    state.deviceConfig = cfg
    updateSyncStatus()
}

void zwaveEvent(hubitat.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport cmd, Integer ep) {
    logDebug "${cmd}"
    List<String> members = (cmd.nodeId ?: []).collect { String.format("%02X", it as Integer) }
    members.addAll((cmd.multiChannelNodeIds ?: []).collect { String.format("%02X-%d", it.nodeId as Integer, it.endPointId as Integer) })
    updateDataValue("associationGroup${cmd.groupingIdentifier}", members ? members.join(", ") : "none")

    if (cmd.groupingIdentifier == 1) {
        boolean ok = (cmd.multiChannelNodeIds ?: []).any { (it.nodeId as Integer) == zwaveHubNodeId && (it.endPointId as Integer) == 0 }
        if (!ok) log.warn "${device.displayName}: the hub is not in the lifeline as a multi-channel association, so button and dimmer reports cannot be told apart. Run 'Repair Lifeline'."
    }
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd, Integer ep) {
    logDebug "${cmd}"
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd, Integer ep) {
    logDebug "${cmd}"
    String fw = "${cmd.applicationVersion}.${cmd.applicationSubVersion.toString().padLeft(2, '0')}"
    String previous = device.getDataValue("firmwareVersion")
    updateDataValue("firmwareVersion", fw)
    shareFirmwareVersion(fw)
    logInfo "firmware ${fw}${previous && previous != fw ? " (was ${previous})" : ""}"
    if (fw != previous) {
        // How single presses are reported may change, and parameter 9 needs 1.08: detect and apply again.
        state.remove("singlePressScenes")
        primaryChildren().each { cd ->
            try {
                cd.configure()
            } catch (Exception ignored) {
                logDebug "${cd.displayName} has no configure() (generic component driver?)"
            }
        }
    }
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd, Integer ep) {
    logDebug "${cmd}"
    updateDataValue("MSR", String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId))
}

void zwaveEvent(hubitat.zwave.Command cmd, Integer ep) {
    logDebug "Unhandled${ep ? " (endpoint ${ep})" : ""}: ${cmd}"
}

/* ---------- Events ---------- */

private void switchEvent(Integer ep, Integer value, String type) {
    def cd = childFor(ep)
    if (!cd) return
    String v = value ? "on" : "off"
    sendChildEvents(cd, [[name: "switch", value: v, type: type, descriptionText: "${cd.displayName} was turned ${v} [${type}]"]])
}

private void dimmerEvents(Integer value) {
    if (value == null || value == 0xFE) return // 0xFE = unknown
    def cd = childFor(DIMMER_EP)
    if (!cd) return
    String type = eventType(DIMMER_EP)
    if (value == 0) {
        sendChildEvents(cd, [[name: "switch", value: "off", type: type, descriptionText: "${cd.displayName} was turned off [${type}]"]])
        return
    }
    List<Map> events = [[name: "switch", value: "on", type: type, descriptionText: "${cd.displayName} was turned on [${type}]"]]
    if (value <= 99) {
        Integer level = value == 99 ? 100 : value // the device's maximum is 99; show it as 100%
        events << [name: "level", value: level, unit: "%", type: type, descriptionText: "${cd.displayName} level is ${level}% [${type}]"]
    }
    sendChildEvents(cd, events)
}

private void buttonEvent(Integer button, String action, String type = "physical") {
    if (button == null || !(button in BUTTON_EPS)) return
    String text = "${device.displayName} button ${button} was ${action} [${type}]"
    if (txtEnable) log.info text
    sendEvent(name: action, value: button, isStateChange: true, type: type, descriptionText: text)
}

private void sendChildEvents(cd, List<Map> events) {
    cd.parse(events)
}

/* ---------- Switching ---------- */

// In the default "LED only" mode (parameter 19 + 8 per button = 0) a Get reports the button's own state, not the
// LED, which would flip the switch back. The device reports the new LED state by itself, so only read back otherwise.
private void buttonSwitch(Integer ep, Integer value) {
    markDigital(ep)
    List cmds = [endpointCommand(ep, zwave.switchBinaryV1.switchBinarySet(switchValue: value))]
    if (paramValue(19 + (ep - 1) * 8)) cmds << endpointCommand(ep, zwave.switchBinaryV1.switchBinaryGet())
    sendCommands(cmds, 500)
}

// secs == null uses the device's own duration (parameter 3 for on/off, parameter 2 for dimming).
private void dimmerSet(Integer value, Integer secs) {
    Integer duration = secs == null ? 0xFF : encodeDuration(secs)
    Integer expected = secs != null ? secs : decodeDuration(paramValue(value in [0, 0xFF] ? 3 : 2) ?: 0)
    markDimmerDigital(expected)
    sendCommands([endpointCommand(DIMMER_EP, zwave.switchMultilevelV4.switchMultilevelSet(value: value, dimmingDuration: duration))])
    schedulePoll(expected)
}

/* ---------- Parameters ---------- */

private List<Map> deviceParameters() {
    List<Map> result = []
    PARAMS.each { p ->
        def raw = settings["configParam${p.num}".toString()]
        if (raw == null || raw.toString().trim() == "") raw = legacySetting(p.num) ?: p.defaultValue
        Long value = p.color ? colorToParam(raw?.toString()) : toLong(raw)
        result << [num: p.num, size: p.size, value: value] // null = no longer managed
    }
    return result
}

private void storeDesired(List<Map> params) {
    if (!params) return
    Map desired = state.desiredConfig ?: [:]
    params.each {
        String key = "${it.num}".toString()
        if (it.value == null) {
            desired.remove(key) // preference cleared: stop enforcing it, the device keeps its current value
        } else {
            desired[key] = [size: it.size as Integer, value: it.value as Long]
        }
    }
    state.desiredConfig = desired
}

private List syncCommands() {
    List cmds = []
    Map desired = state.desiredConfig ?: [:]
    Map reported = state.deviceConfig ?: [:]
    desired.each { String key, Map d ->
        Long want = d.value as Long
        Long have = reported[key] as Long
        if (want != have) {
            Integer num = key as Integer
            Integer size = d.size as Integer
            logDebug "Parameter ${num}: ${have} -> ${want}"
            cmds << zwave.configurationV1.configurationSet(parameterNumber: num, size: size, scaledConfigurationValue: toSigned(want, size))
            cmds << zwave.configurationV1.configurationGet(parameterNumber: num)
        }
    }
    updateSyncStatus()
    return cmds
}

private void updateSyncStatus() {
    Map desired = state.desiredConfig ?: [:]
    Map reported = state.deviceConfig ?: [:]
    Integer pending = desired.count { String key, Map d -> (d.value as Long) != (reported[key] as Long) }
    sendEvent(name: "syncStatus", value: pending ? "${pending} pending" : "synced")
}

// Value the device reported, falling back to the desired value.
private Integer paramValue(Integer num) {
    String key = "${num}".toString()
    def reported = (state.deviceConfig ?: [:])[key]
    if (reported != null) return reported as Integer
    def desired = (state.desiredConfig ?: [:])[key]
    return desired != null ? desired.value as Integer : null
}

/* ---------- Associations ---------- */

private List associationGetCommands() {
    return (1..14).collect { zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier: it) }
}

/* ---------- Children ---------- */

private void createChildDevices() {
    BUTTON_EPS.each { ep -> addChildIfMissing(ep, "Matrix ZDB 5100 (Button)", "Button ${ep}") }
    addChildIfMissing(DIMMER_EP, "Matrix ZDB 5100 (Dimmer)", "Dimmer")
}

private void addChildIfMissing(Integer ep, String driver, String label) {
    if (childrenFor(ep)) return
    String dni = "${device.deviceNetworkId}-${ep}"
    try {
        addChildDevice("mhtorp", driver, dni, [name: "Matrix ZDB 5100 ${label}", label: "${device.displayName} ${label}", isComponent: true])
        logInfo "created ${label}"
    } catch (Exception e) {
        log.error "${device.displayName}: could not create ${label}. Is the driver '${driver}' installed? ${e.message}"
    }
}

// A child keeps its network ID when the parent's changes (re-inclusion, hub migration), so children are matched on
// the endpoint suffix only, as version 1.x did. The oldest comes first.
private List childrenFor(Integer ep) {
    return (childDevices ?: []).findAll { endpointOf(it) == ep }.sort { toLong(it.id) ?: Long.MAX_VALUE }
}

// The child that gets the endpoint's events: the oldest if there are duplicates.
private def childFor(Integer ep) {
    List found = childrenFor(ep)
    return found ? found[0] : null
}

private List primaryChildren() {
    return (BUTTON_EPS + [DIMMER_EP]).collect { childFor(it) }.findAll { it != null }
}

private List duplicateChildren() {
    return (BUTTON_EPS + [DIMMER_EP]).collectMany { childrenFor(it).drop(1) }
}

private void warnDuplicateChildren() {
    List extra = duplicateChildren()
    if (extra) {
        String names = extra.collect { "${it.displayName} (${it.deviceNetworkId})" }.join(", ")
        log.warn "${device.displayName}: ${names} duplicate older children of the same endpoints " +
            "and get no events. Run 'Remove Duplicate Children' to delete them."
    }
}

// "<parent network ID>-<endpoint>"; null if the ID has no endpoint suffix.
private Integer endpointOf(cd) {
    def m = (cd.deviceNetworkId ?: "") =~ /-(\d+)$/
    return m.find() ? (m.group(1) as Integer) : null
}

private boolean isDimmer(cd, String command) {
    if (endpointOf(cd) == DIMMER_EP) return true
    log.warn "${device.displayName}: ${command} is only supported by the dimmer"
    return false
}

/* ---------- Z-Wave helpers ---------- */

private List refreshCommands() {
    return (BUTTON_EPS + [DIMMER_EP]).collect { endpointGet(it) }
}

private String endpointGet(Integer ep) {
    return ep == DIMMER_EP ?
        endpointCommand(ep, zwave.switchMultilevelV4.switchMultilevelGet()) :
        endpointCommand(ep, zwave.switchBinaryV1.switchBinaryGet())
}

// Multi Channel encapsulation to an endpoint.
private String endpointCommand(Integer ep, hubitat.zwave.Command cmd) {
    return zwave.multiChannelV4.multiChannelCmdEncap(destinationEndPoint: ep).encapsulate(cmd).format()
}

private List<String> commands(List cmds, Long delay = 400) {
    return delayBetween(cmds.collect { zwaveSecureEncap(it) }, delay)
}

private void sendCommands(List cmds, Long delay = 400) {
    if (cmds) sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

// Reports from an endpoint arriving before this time are attributed to a hub command.
private void markDigital(Integer ep, Integer transitionSecs = 0) {
    DIGITAL_UNTIL.put(deviceKey(ep), now() + (transitionSecs * 1000L) + 3000L)
}

// The dimmer plus the buttons that control it, whose state follows the dimmer.
private void markDimmerDigital(Integer transitionSecs) {
    markDigital(DIMMER_EP, transitionSecs)
    dimmerButtons().each { markDigital(it, transitionSecs) }
}

// A scene notification means the button was pressed by hand; reports after it are physical even if a hub
// command was sent a moment before. A button that controls the dimmer also ends the dimmer's window.
private void endDigital(Integer button) {
    List<Integer> eps = button in dimmerButtons() ? [DIMMER_EP] + dimmerButtons() : [button]
    eps.each { DIGITAL_UNTIL.remove(deviceKey(it)) }
}

// Buttons that control the dimmer (parameter 1 bitmask).
private List<Integer> dimmerButtons() {
    Integer mask = paramValue(1)
    if (mask == null) mask = 1 // device default: button 1
    return BUTTON_EPS.findAll { mask & (1 << (it - 1)) }
}

private String eventType(Integer ep) {
    Long until = DIGITAL_UNTIL.get(deviceKey(ep))
    return (until != null && now() <= until) ? "digital" : "physical"
}

// A button's switch report while held, or the first one after the release, is not a push.
private boolean pushSuppressed(Integer button) {
    String key = deviceKey(button)
    Long until = PUSH_SUPPRESSED_UNTIL.get(key)
    if (until == null || now() > until) return false
    if (until <= now() + 2000L) PUSH_SUPPRESSED_UNTIL.remove(key) // released: only swallow one report
    return true
}

// In timer modes (parameter 16 + 8 per button) the device flips the button when the timer expires.
private boolean isTimerReport(Integer button, Integer value) {
    Integer mode = paramValue(16 + (button - 1) * 8)
    return (mode == 1 && !value) || (mode == 2 && value)
}

// Pushes come from switch reports unless the device has been seen sending Central Scene for single presses
// (and Central Scene, parameter 7, is still on).
private boolean reportPushesEnabled() {
    if (settings.fakeButtonPush == false) return false
    return !(state.singlePressScenes && paramValue(7) != 0)
}

// The dimmer child uses the firmware version to tell whether parameter 9 (turn-on level) works.
private void shareFirmwareVersion(String fw) {
    childDevices.each { cd ->
        try {
            cd.updateDataValue("firmwareVersion", fw)
        } catch (Exception e) {
            logDebug "could not store the firmware version on ${cd.displayName}: ${e.message}"
        }
    }
}

// Safety-net poll after a dimmer command, in case the device's own report is missed.
private void schedulePoll(Integer transitionSecs = 0) {
    runInMillis((transitionSecs * 1000L) + 2000L, "pollDimmer")
}

// True if the same id arrived from this device in the last 3 seconds.
private boolean isRetransmission(Map<String, List> seen, String id) {
    List previous = seen.put(deviceKey(0), [id, now()])
    return previous != null && previous[0] == id && now() - (previous[1] as Long) < 3000L
}

private String deviceKey(Integer ep) {
    return "${device.id}:${ep}".toString()
}

// Seconds (0 or more), or null when not given.
private Integer durationSeconds(transitionTime) {
    Integer secs = toInt(transitionTime)
    return secs != null ? Math.max(0, secs) : null
}

// Z-Wave duration: 0-127 = seconds, 0x80-0xFE = 1-127 minutes.
private Integer encodeDuration(Integer secs) {
    if (secs <= 127) return secs
    Integer minutes = Math.round(secs / 60.0) as Integer
    return Math.min(0xFE, 0x7F + minutes)
}

// Parameter durations: 0-127 = seconds, 128-255 = 1-128 minutes.
private Integer decodeDuration(Integer value) {
    if (value == null || value < 0) return 0
    return value <= 127 ? value : (value - 127) * 60
}

private Long unsignedValue(List bytes) {
    return (bytes ?: []).inject(0L) { Long acc, b -> (acc << 8) | ((b as Integer) & 0xFF) }
}

private Integer toSigned(Long value, Integer size) {
    Long half = 1L << (8 * size - 1)
    return (value >= half ? value - (1L << (8 * size)) : value) as Integer
}

// "RRGGBB" -> 4-byte parameter value with the last byte 0.
private Long colorToParam(String hex) {
    List<Integer> rgb = hexToRgb(hex)
    if (rgb == null) {
        if (hex) log.warn "${device.displayName}: '${hex}' is not a colour in RRGGBB format"
        return null
    }
    return ((rgb[0] as Long) << 24) | ((rgb[1] as Long) << 16) | ((rgb[2] as Long) << 8)
}

private List<Integer> hexToRgb(String hex) {
    String h = hex?.trim()?.replaceFirst(/^#/, "")
    if (!h || !(h ==~ /[0-9A-Fa-f]{6}/)) return null
    return [0, 2, 4].collect { Integer.parseInt(h.substring(it, it + 2), 16) }
}

private List<Integer> currentHsv(cd) {
    return rgbToHsv(hexToRgb(cd.currentValue("color")?.toString()) ?: [255, 255, 255])
}

// Switch Color component IDs: 0 = warm white, 2 = red, 3 = green, 4 = blue.
// With enhanced LED control the warm white value selects what the colour applies to (see componentSetColor).
private hubitat.zwave.Command colorSet(List<Integer> rgb, Integer warmWhite) {
    return zwave.switchColorV1.switchColorSet(colorComponents: [0: warmWhite, 2: rgb[0], 3: rgb[1], 4: rgb[2]])
}

// Hubitat colour model (hue, saturation and level 0-100) -> [red, green, blue] 0-255.
private List<Integer> hsvToRgb(Integer hue, Integer saturation, Integer level) {
    double h = (hue % 100) * 6.0 / 100.0
    double s = saturation / 100.0
    double v = level / 100.0
    int sector = Math.floor(h) as int
    double f = h - sector
    double p = v * (1 - s)
    double q = v * (1 - s * f)
    double t = v * (1 - s * (1 - f))
    List<Double> rgb
    switch (sector) {
        case 0: rgb = [v, t, p]; break
        case 1: rgb = [q, v, p]; break
        case 2: rgb = [p, v, t]; break
        case 3: rgb = [p, q, v]; break
        case 4: rgb = [t, p, v]; break
        default: rgb = [v, p, q]
    }
    return rgb.collect { Math.round(it * 255) as Integer }
}

// [red, green, blue] 0-255 -> [hue, saturation, level] 0-100.
private List<Integer> rgbToHsv(List<Integer> rgb) {
    double r = rgb[0] / 255.0
    double g = rgb[1] / 255.0
    double b = rgb[2] / 255.0
    double max = Math.max(r, Math.max(g, b))
    double d = max - Math.min(r, Math.min(g, b))
    double h = 0
    if (d > 0) {
        if (max == r) h = (g - b) / d
        else if (max == g) h = (b - r) / d + 2
        else h = (r - g) / d + 4
        if (h < 0) h += 6
    }
    double s = max == 0 ? 0 : d / max
    return [Math.round(h * 100 / 6) as Integer, Math.round(s * 100) as Integer, Math.round(max * 100) as Integer]
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

/* ---------- Migration from 1.x ---------- */

private def legacySetting(Integer num) {
    if (num == 10 && settings.enhancedLedControl != null) return settings.enhancedLedControl ? "1" : "0"
    return null
}

private void migrateSettings() {
    if (settings.enhancedLedControl != null) {
        if (settings.configParam10 == null) device.updateSetting("configParam10", [value: settings.enhancedLedControl ? "1" : "0", type: "enum"])
        device.removeSetting("enhancedLedControl")
    }
}

// Removes state and attributes left behind by version 1.x.
private void cleanupLegacyState() {
    List<String> keys = ["1", "2", "3", "4", "bin", "associationGroups", "defaultG1", "defaultG2", "defaultG3"]
    (1..14).each { keys << "desiredAssociation${it}".toString(); keys << "actualAssociation${it}".toString() }
    keys.each { state.remove(it) }
    device.deleteCurrentState("groups")
}

/* ---------- Logging ---------- */

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${device.displayName}: ${msg}"
}
