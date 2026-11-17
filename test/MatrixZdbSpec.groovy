package matrix

import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.api.common_api.DeviceWrapper
import me.biocomp.hubitat_ci.api.common_api.HubMultiAction
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.api.device_api.zwave.Command
import me.biocomp.hubitat_ci.api.device_api.zwave.Zwave
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.basicv1.BasicReport
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.centralscenev3.CentralSceneNotification
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.configurationv1.ConfigurationReport
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.multichannelv4.MultiChannelCmdEncap
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.supervisionv1.SupervisionGet
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.switchbinaryv1.SwitchBinaryReport
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.switchmultilevelv4.SwitchMultilevelReport
import me.biocomp.hubitat_ci.api.device_api.zwave.commands.versionv1.VersionReport
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification

/**
 * Exercises the parent and both child drivers inside hubitat_ci's sandbox, wired together the way the hub does it:
 * children call parent.componentXxx(device), and the parent calls child.parse(events) on the child device.
 *
 * Z-Wave factory methods, command classes and fields come from hubitat_ci's mirror of the real Hubitat API, so a
 * wrong factory, class, field name or field type fails here. The radio, the hub scheduler and Z-Wave JS itself are
 * not simulated: scheduled jobs are recorded and the tests call them directly.
 *
 * Outgoing commands are recorded as text. An endpoint command reads "EP5>SwitchMultilevelGet".
 *
 * Run with test/run-tests.sh, which sets up hubitat_ci (with small compatibility patches) in Docker.
 */
class MatrixZdbSpec extends Specification {
    static final File DIR = new File(System.getenv("DRIVER_DIR") ?: "drivers")
    static final File PARENT = new File(DIR, "matrix_zdb.groovy")
    static final File BUTTON = new File(DIR, "matrix_zdb_button.groovy")
    static final File DIMMER = new File(DIR, "matrix_zdb_dimmer.groovy")
    static final String API = "me.biocomp.hubitat_ci.api.device_api.zwave"

    // On Hubitat a blank preference reads as null; hubitat_ci would otherwise substitute a placeholder.
    static final List<String> PARENT_INPUTS = [7, 8, 10, 11, 12, 13, 14, 15].collect { "configParam${it}".toString() } + ["fakeButtonPush"]
    static final List<String> BUTTON_INPUTS = ["buttonMode", "timerDuration", "binarySwitchMode", "ledMode", "colorCommandMode",
                                               "onColor", "offColor", "group4Enabled", "group4OnLevel", "group4OffLevel"]
    static final List<String> DIMMER_INPUTS = ["dimmerButtons", "dimDuration", "onOffDuration", "dimmingMode", "minLevel",
                                               "maxLevel", "onLevel"]
    static final List<String> BUTTON_ACTIONS = ["pushed", "held", "released", "doubleTapped"]

    long clock = 1_000_000L
    def parent
    def parseResult = null
    Map parentState = [:]
    Map parentAttrs = [:]
    List<Map> parentEvents = []
    Map parentData = [:]
    Map parentSettingUpdates = [:]
    List<String> parentRemoved = []
    List<String> deletedAttrs = []
    List<String> hubCommands = []
    List<String> scheduled = []
    List<String> logs = []
    List<Map> created = []
    List<String> deletedChildren = []
    Map<Integer, Map> kids = [:]

    // ---------- harness ----------

    private Log logStub(String who) {
        Stub(Log) {
            debug(_ as String) >> { String s -> logs << "debug: ${s}".toString() }
            info(_ as String) >> { String s -> logs << "info: ${s}".toString() }
            warn(_ as String) >> { String s -> logs << "warn: ${s}".toString() }
            error(_ as String) >> { String s -> logs << "error: ${s}".toString() }
        }
    }

    /** JDK proxy implementing the API-mirror type plus Command. Methods are answered by name; closures are called. */
    private <T> T proxy(Class<T> type, Map answers) {
        def handler = { p, java.lang.reflect.Method m, Object[] args ->
            if (m.name == "hashCode") return System.identityHashCode(p)
            if (m.name == "equals") return p.is(args[0])
            def answer = answers[m.name]
            return answer instanceof Closure ? answer.call(*(args ?: [])) : answer
        } as java.lang.reflect.InvocationHandler
        Class[] ifaces = (Command.isAssignableFrom(type) ? [type] : [type, Command]) as Class[]
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.classLoader, ifaces, handler)
    }

    /** Builds a fake outgoing command after checking every argument against the real API's setters. */
    private Object cmd(Class type, Map args) {
        args.each { k, v ->
            def setter = type.methods.find { it.name == "set${k.toString().capitalize()}" && it.parameterTypes.length == 1 }
            assert setter != null: "${type.simpleName} has no field '${k}'"
            Class p = setter.parameterTypes[0]
            if (p in [Boolean, boolean]) assert v instanceof Boolean: "${type.simpleName}.${k} is Boolean, driver passed ${v?.getClass()?.simpleName}"
            if (Number.isAssignableFrom(p) || (p.isPrimitive() && p != boolean)) assert v instanceof Number: "${type.simpleName}.${k} is ${p.simpleName}, driver passed ${v?.getClass()?.simpleName}"
            if (List.isAssignableFrom(p)) assert v instanceof List: "${type.simpleName}.${k} is a List, driver passed ${v?.getClass()?.simpleName}"
            if (Map.isAssignableFrom(p)) assert v instanceof Map: "${type.simpleName}.${k} is a Map, driver passed ${v?.getClass()?.simpleName}"
        }
        String text = "${type.simpleName}${args ? args.sort { it.key.toString() } : ''}"
        Map answers = [format: text, toString: text]
        if (type.simpleName == "MultiChannelCmdEncap") {
            answers.encapsulate = { inner -> proxy(type, [format: "EP${args.destinationEndPoint}>${inner.format()}".toString()]) }
        }
        return proxy(type, answers)
    }

    /** zwave.<commandClass>.<factory>(args), checked against the API mirror's factory methods and command classes. */
    private Object zwaveCommand(String commandClass, String factory, Object[] args) {
        Class cc = Class.forName("${API}.commandclasses.${commandClass}")
        assert cc.methods.any { it.name == factory }: "zwave.${commandClass.uncapitalize()} has no factory '${factory}'"
        Class type = Class.forName("${API}.commands.${commandClass.toLowerCase()}.${factory.capitalize()}")
        return cmd(type, args ? args[0] as Map : [:])
    }

    private Zwave fakeZwave() {
        Closure make = { String name, String factory, Object[] args -> zwaveCommand(name, factory, args) }
        Map<String, CommandClassFactory> f = ["AssociationV2", "ConfigurationV1", "ManufacturerSpecificV2", "MultiChannelAssociationV2",
                                              "MultiChannelV4", "SupervisionV1", "SwitchBinaryV1", "SwitchColorV1", "SwitchMultilevelV4", "VersionV1"]
                .collectEntries { [it, new CommandClassFactory(name: it, make: make)] }
        return Stub(Zwave) {
            getAssociationV2() >> f.AssociationV2
            getConfigurationV1() >> f.ConfigurationV1
            getManufacturerSpecificV2() >> f.ManufacturerSpecificV2
            getMultiChannelAssociationV2() >> f.MultiChannelAssociationV2
            getMultiChannelV4() >> f.MultiChannelV4
            getSupervisionV1() >> f.SupervisionV1
            getSwitchBinaryV1() >> f.SwitchBinaryV1
            getSwitchColorV1() >> f.SwitchColorV1
            getSwitchMultilevelV4() >> f.SwitchMultilevelV4
            getVersionV1() >> f.VersionV1
            parse(_ as String, _ as Map) >> { parseResult }
        }
    }

    /** The child device as both the parent and the child driver see it. */
    private ChildDeviceWrapper childWrapper(int ep, Map kid) {
        GroovyMock(ChildDeviceWrapper) {
            _ * getDeviceNetworkId() >> { kid.dni ?: "2A-${ep}".toString() }
            _ * getDisplayName() >> kid.name
            _ * getId() >> { kid.id ?: "${100 + ep}".toString() }
            _ * currentValue(_ as String) >> { String n -> kid.attrs[n] }
            _ * currentValue(_ as String, _) >> { String n, b -> kid.attrs[n] }
            _ * parse(_ as List) >> { args -> kid.script.parse(args[0]) } // one untyped parameter = the argument list
            _ * asBoolean() >> true // Groovy truth; a GroovyMock would answer null
            _ * desiredParameters() >> { kid.script.desiredParameters() }
            _ * configure() >> { kid.script.configure() }
            _ * getDataValue(_ as String) >> { String n -> kid.data[n] }
            _ * updateDataValue(_ as String, _ as String) >> { String n, String v -> kid.data[n] = v }
            // Like the hub, later calls see the new value.
            _ * updateSetting(_ as String, _) >> { String n, v -> kid.settingUpdates[n] = v; kid.settingValues?.put(n, v instanceof Map ? v.value : v) }
            _ * removeSetting(_ as String) >> { String n -> kid.removed << n; kid.settingValues?.put(n, null) }
        }
    }

    private void loadChild(int ep, Map settings) {
        Map kid = kids[ep]
        boolean dimmer = ep == 5
        Map values = (dimmer ? DIMMER_INPUTS : BUTTON_INPUTS).collectEntries { [it, null] } + [logEnable: true, txtEnable: true] + settings
        kid.settingValues = values
        DeviceExecutor api = Stub(DeviceExecutor) {
            getState() >> kid.state
            getDevice() >> kid.wrapper
            getLog() >> logStub(kid.name)
            getParent() >> { parent }
            now() >> { clock }
            sendEvent(_ as Map) >> { Map m -> kid.events << m; kid.attrs[m.name] = m.value }
            runIn(_ as Long, _ as String) >> { Long s, String h -> scheduled << "${kid.name}:${h}".toString() }
        }
        kid.script = new HubitatDeviceSandbox(dimmer ? DIMMER : BUTTON).run(api: api, userSettingValues: values,
                validationFlags: [Flags.AllowReadingNonInputSettings])
    }

    /**
     * Loads the parent and, unless withChildren is false, all five children with their drivers.
     * childSettings: endpoint -> settings for that child.
     */
    private void load(Map settings = [:], Map<Integer, Map> childSettings = [:], boolean withChildren = true) {
        (1..5).each { ep ->
            Map kid = [name: ep == 5 ? "Dimmer" : "Button ${ep}".toString(), state: [:], attrs: [:], events: [],
                       settingUpdates: [:], removed: [], data: [:], exists: withChildren]
            kid.wrapper = childWrapper(ep, kid)
            kids[ep] = kid
        }
        Zwave zw = fakeZwave()
        DeviceWrapper device = GroovyMock(DeviceWrapper) {
            _ * getDisplayName() >> "ZDB"
            _ * asBoolean() >> true
            _ * getDeviceNetworkId() >> "2A"
            _ * getId() >> "42"
            _ * getDataValue(_ as String) >> { String n -> parentData[n] }
            _ * currentValue(_ as String) >> { String n -> parentAttrs[n] }
            _ * currentValue(_ as String, _) >> { String n, b -> parentAttrs[n] }
            _ * deleteCurrentState(_ as String) >> { String n -> deletedAttrs << n }
            _ * updateSetting(_ as String, _) >> { String n, v -> parentSettingUpdates[n] = v }
            _ * removeSetting(_ as String) >> { String n -> parentRemoved << n }
        }
        DeviceExecutor api = Stub(DeviceExecutor) {
            getState() >> parentState
            getZwave() >> zw
            getDevice() >> device
            getLog() >> logStub("ZDB")
            now() >> { clock }
            getZwaveHubNodeId() >> (short) 1
            zwaveSecureEncap(_ as Command) >> { Command c -> assert c != null; c.format() }
            zwaveSecureEncap(_ as String) >> { String s -> s }
            delayBetween(_ as List, _ as Long) >> { List l, Long d -> l.collectMany { [it, "delay ${d}".toString()] }.dropRight(1) }
            sendEvent(_ as Map) >> { Map m -> parentEvents << m; parentAttrs[m.name] = m.value }
            runIn(_ as Long, _ as String) >> { Long s, String h -> scheduled << h }
            runInMillis(_ as Long, _ as String) >> { Long s, String h -> scheduled << h }
            sendHubCommand(_ as HubMultiAction) >> { HubMultiAction a -> hubCommands.addAll(a.actionList*.action) }
            updateDataValue(_ as String, _ as String) >> { String n, String v -> parentData[n] = v }
            getChildDevice(_ as String) >> { String dni -> kids.values().find { it.exists && it.wrapper.deviceNetworkId == dni }?.wrapper }
            getChildDevices() >> { kids.values().findAll { it.exists }*.wrapper }
            deleteChildDevice(_ as String) >> { String dni ->
                deletedChildren << dni
                kids.values().find { it.exists && it.wrapper.deviceNetworkId == dni }?.exists = false
            }
            addChildDevice(_ as String, _ as String, _ as String, _ as Map) >> { String ns, String type, String dni, Map props ->
                created << [ns: ns, type: type, dni: dni, props: props]
                Map kid = kids[dni.split("-")[-1] as Integer]
                kid.exists = true
                kid.wrapper
            }
        }
        Map values = PARENT_INPUTS.collectEntries { [it, null] } + [logEnable: true, txtEnable: true] + settings
        parent = new HubitatDeviceSandbox(PARENT).run(api: api, userSettingValues: values,
                validationFlags: [Flags.AllowReadingNonInputSettings])
        if (withChildren) (1..5).each { loadChild(it, childSettings[it] ?: [:]) }
    }

    /** Incoming report: getters return the given values. */
    private <T> T report(Class<T> type, Map values) {
        Map answers = [toString: "${type.simpleName}${values}".toString()]
        values.each { k, v -> answers["get${k.toString().capitalize()}".toString()] = v }
        return proxy(type, answers)
    }

    /** Delivers a report through parse() as a Multi Channel encapsulated frame from an endpoint. */
    private void fromEndpoint(int ep, Object inner) {
        parseResult = Stub(MultiChannelCmdEncap) {
            getSourceEndPoint() >> (short) ep
            getDestinationEndPoint() >> (short) 0
            encapsulatedCommand(_ as Map) >> inner
        }
        parent.parse("zw device: 2A, command: 600D, payload: ...")
    }

    private void scene(int button, int key, int seq) {
        parent.zwaveEvent(report(CentralSceneNotification, [sceneNumber: (short) button, keyAttributes: (short) key, sequenceNumber: (short) seq]), 0)
    }

    private void configReport(int num, List<Integer> bytes) {
        parent.zwaveEvent(report(ConfigurationReport, [parameterNumber: (short) num, size: (short) bytes.size(),
                                                       configurationValue: bytes.collect { it as short }]), 0)
    }

    private List buttonEvents() {
        parentEvents.findAll { it.name in BUTTON_ACTIONS }.collect { [it.name, it.value] }
    }

    private List<String> configSets() {
        hubCommands.findAll { it.startsWith("ConfigurationSet") }
    }

    private static String set(int num, long value, int size) {
        "ConfigurationSet[parameterNumber:${num}, scaledConfigurationValue:${value}, size:${size}]".toString()
    }

    // ---------- loading ----------

    def "all three drivers compile and pass hubitat_ci metadata and preference validation"() {
        when:
        load()

        then:
        parent.producedPreferences.size() == 11 // 8 parameters, fake push, 2 logging switches
        parent.producedDefinition.capabilities.containsAll(["PushableButton", "HoldableButton", "ReleasableButton", "DoubleTapableButton", "Configuration", "Refresh"])
        kids[1].script.producedPreferences.size() == 12
        kids[1].script.producedDefinition.capabilities.containsAll(["Switch", "ColorControl", "Flash", "Refresh", "Configuration"])
        kids[5].script.producedPreferences.size() == 9
        kids[5].script.producedDefinition.capabilities.containsAll(["Switch", "SwitchLevel", "ChangeLevel", "LevelPreset", "Refresh", "Configuration"])
    }

    def "configure creates missing children, queries the device and writes the driver defaults"() {
        given:
        load([:], [:], false)

        when:
        parent.configure()

        then:
        created*.dni == ["2A-1", "2A-2", "2A-3", "2A-4", "2A-5"]
        created*.type == ["Matrix ZDB 5100 (Button)"] * 4 + ["Matrix ZDB 5100 (Dimmer)"]
        created.every { it.ns == "mhtorp" && it.props.isComponent == true }
        hubCommands.containsAll(["VersionGet", "ManufacturerSpecificGet", "MultiChannelAssociationGet[groupingIdentifier:1]",
                                 "MultiChannelAssociationGet[groupingIdentifier:14]", "EP1>SwitchBinaryGet", "EP4>SwitchBinaryGet", "EP5>SwitchMultilevelGet"])
        configSets() == [set(10, 1, 1), set(14, 0xFF555500L - (1L << 32), 4)] // enhanced LED control, colour balance FF5555
        parentAttrs.numberOfButtons == 4
        parentAttrs.syncStatus == "2 pending"
        parentData.driverVersion == "2.0.0"
    }

    def "children created under an earlier parent network ID are found by endpoint, so configure creates none"() {
        given:
        load()
        (1..5).each { kids[it].dni = "1F-${it}".toString() }

        when:
        parent.configure()
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 40]))
        fromEndpoint(2, report(BasicReport, [value: (short) 0xFF]))

        then:
        created.isEmpty()
        kids[5].attrs.level == 40
        kids[2].attrs.switch == "on"
    }

    def "with duplicate children the oldest gets the events, and removeDuplicateChildren deletes the others"() {
        given:
        load()
        kids[1].dni = "1F-1"
        kids[11] = [name: "Button 1 copy", state: [:], attrs: [:], events: [], settingUpdates: [:], removed: [], data: [:],
                    exists: true, dni: "2A-1", id: "301"]
        kids[11].wrapper = childWrapper(1, kids[11])
        loadChild(11, [:])

        when:
        fromEndpoint(1, report(BasicReport, [value: (short) 0xFF]))
        parent.configure()
        then:
        kids[1].attrs.switch == "on"
        kids[11].events.isEmpty()
        created.isEmpty()
        logs.any { it.startsWith("warn:") && it.contains("Button 1 copy") && it.contains("Remove Duplicate Children") }

        when:
        parent.removeDuplicateChildren()
        then:
        deletedChildren == ["2A-1"]
        !kids[11].exists
        kids[1].exists
    }

    def "configure writes every desired parameter again, even if the cache says it is set"() {
        given:
        load()
        parentState.deviceConfig = ["10": 1, "14": 0xFF555500L]

        when:
        parent.configure()

        then:
        configSets() == [set(10, 1, 1), set(14, 0xFF555500L - (1L << 32), 4)]
        created.isEmpty()
    }

    def "configure removes state left by version 1.x"() {
        given:
        load()
        parentState.putAll(["1": 0, bin: 3, defaultG1: ["01-0"], desiredAssociation2: "05", actualAssociation14: [], associationGroups: 14])

        when:
        parent.configure()

        then:
        !parentState.keySet().any { it in ["1", "bin", "defaultG1", "desiredAssociation2", "actualAssociation14", "associationGroups"] }
        deletedAttrs.contains("groups")
    }

    // ---------- buttons ----------

    def "a button's lifeline report updates its child and gives a pushed event"() {
        given:
        load()

        when:
        fromEndpoint(2, report(BasicReport, [value: (short) 0xFF]))

        then:
        kids[2].attrs.switch == "on"
        kids[2].events.last().type == "physical"
        buttonEvents() == [["pushed", 2]]
        logs.contains("info: Button 2 was turned on [physical]")
    }

    def "with fake pushes off, pushed comes from Central Scene instead"() {
        given:
        load([fakeButtonPush: false])

        when:
        fromEndpoint(1, report(BasicReport, [value: (short) 0xFF]))
        scene(1, 0, 1)

        then:
        kids[1].attrs.switch == "on"
        buttonEvents() == [["pushed", 1]]
    }

    def "once the device sends Central Scene for single presses, pushes come from there and not from switch reports"() {
        given:
        load()

        when: "first press: the switch report gives the push, the scene that follows is not counted twice"
        fromEndpoint(2, report(BasicReport, [value: (short) 0xFF]))
        scene(2, 0, 1)
        then:
        buttonEvents() == [["pushed", 2]]
        parentState.singlePressScenes == true

        when: "later presses: only the scene gives a push, so a timer expiring or a press during a hub command is no problem"
        parentEvents.clear()
        clock += 5_000
        fromEndpoint(2, report(BasicReport, [value: (short) 0]))
        scene(2, 0, 2)
        then:
        buttonEvents() == [["pushed", 2]]
        kids[2].attrs.switch == "off"

        when: "with Central Scene turned off on the device, switch reports give the pushes again"
        parentEvents.clear()
        configReport(7, [0])
        fromEndpoint(2, report(BasicReport, [value: (short) 0xFF]))
        then:
        buttonEvents() == [["pushed", 2]]

        when: "configure forgets the detection, in case the firmware changed"
        parent.configure()
        then:
        !parentState.containsKey("singlePressScenes")
    }

    def "Central Scene: held once per hold, released, double tap, retransmissions and 3x presses ignored"() {
        given:
        load()

        when: "held repeats while the button is down"
        scene(3, 2, 1)
        scene(3, 2, 2)
        scene(3, 2, 3)
        scene(3, 1, 4)
        then:
        buttonEvents() == [["held", 3], ["released", 3]]

        when: "the switch report right after the release is not a push, the next one is"
        parentEvents.clear()
        fromEndpoint(3, report(BasicReport, [value: (short) 0xFF]))
        fromEndpoint(3, report(BasicReport, [value: (short) 0]))
        then:
        buttonEvents() == [["pushed", 3]]

        when:
        parentEvents.clear()
        scene(4, 3, 5)
        scene(4, 3, 5) // retransmission
        scene(4, 4, 6) // pressed 3 times
        then:
        buttonEvents() == [["doubleTapped", 4]]
    }

    def "a lost release stops suppressing pushes after 30 seconds"() {
        given:
        load()
        scene(1, 2, 1)

        when:
        fromEndpoint(1, report(BasicReport, [value: (short) 0xFF]))
        clock += 31_000
        fromEndpoint(1, report(BasicReport, [value: (short) 0]))

        then:
        buttonEvents() == [["held", 1], ["pushed", 1]]
    }

    def "a timer expiring is not a push"() {
        given:
        load()
        configReport(16, [1]) // button 1: turn off after timer

        when:
        fromEndpoint(1, report(BasicReport, [value: (short) 0]))
        fromEndpoint(1, report(BasicReport, [value: (short) 0xFF]))

        then:
        buttonEvents() == [["pushed", 1]]
        kids[1].attrs.switch == "on"
    }

    def "digital button events from the parent's button commands"() {
        given:
        load()

        when:
        parent.push(2)
        parent.hold(3)
        parent.doubleTap(1)

        then:
        buttonEvents() == [["pushed", 2], ["held", 3], ["doubleTapped", 1]]
        parentEvents.every { it.name in BUTTON_ACTIONS ? it.type == "digital" && it.isStateChange : true }
    }

    def "button on/off sends Binary Switch Set to its endpoint, and reads it back unless it only controls the LED"() {
        given:
        load()

        when: "default mode: LED only, where a Get would report the button's own state"
        kids[3].script.on()
        kids[3].script.off()
        fromEndpoint(3, report(SwitchBinaryReport, [value: (short) 0]))
        then:
        hubCommands == ["EP3>SwitchBinarySet[switchValue:255]", "EP3>SwitchBinarySet[switchValue:0]"]
        kids[3].attrs.switch == "off"
        kids[3].events.last().type == "digital"
        buttonEvents().isEmpty()

        when: "button 3 controls its state as well (parameter 35 = 1)"
        hubCommands.clear()
        configReport(35, [1])
        kids[3].script.on()
        then:
        hubCommands == ["EP3>SwitchBinarySet[switchValue:255]", "delay 500", "EP3>SwitchBinaryGet"]
    }

    def "button LED colour: setColor, setHue, flash"() {
        given:
        load()

        when:
        kids[3].script.setColor([hue: 0, saturation: 100, level: 100])
        then:
        hubCommands == ["EP3>SwitchColorSet[colorComponents:[0:16, 2:255, 3:0, 4:0]]"]
        kids[3].attrs.color == "#FF0000"
        kids[3].attrs.hue == 0
        kids[3].attrs.saturation == 100

        when:
        hubCommands.clear()
        kids[3].script.setHue(33)
        then:
        hubCommands == ["EP3>SwitchColorSet[colorComponents:[0:16, 2:5, 3:255, 4:0]]"]
        kids[3].attrs.color == "#05FF00"

        when: "flash blinks the current colour; 500 ms = half period 5"
        hubCommands.clear()
        kids[3].script.flash(500)
        then:
        hubCommands == ["EP3>SwitchColorSet[colorComponents:[0:149, 2:5, 3:255, 4:0]]"]
    }

    def "setColorOn stores the colour as the preference and writes the button's parameter"() {
        given:
        load()

        when:
        kids[2].script.setColorOn(255, 0, 0)
        parent.syncParameters()

        then:
        kids[2].settingUpdates.onColor == [value: "FF0000", type: "text"]
        scheduled.contains("syncParameters")
        configSets() == [set(30, 0xFF000000L - (1L << 32), 4)] // button 2: parameter 24 + 6
    }

    // ---------- dimmer ----------

    def "dimmer reports: 99 shows as 100%, 0 turns off and keeps the level, 0xFE is ignored"() {
        given:
        load()

        when:
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 99]))
        then:
        kids[5].attrs.switch == "on"
        kids[5].attrs.level == 100
        kids[5].events.last().type == "physical"

        when:
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0]))
        then:
        kids[5].attrs.switch == "off"
        kids[5].attrs.level == 100

        when:
        kids[5].events.clear()
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0xFE]))
        then:
        kids[5].events.isEmpty()
    }

    def "during a transition the dimmer's target level is shown, not the starting level"() {
        given:
        load()
        kids[5].attrs.switch = "off"

        when: "as logged by firmware 1.05 when turning on from off"
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0, targetValue: (short) 255, duration: (short) 2]))
        then:
        kids[5].attrs.switch == "on"
        kids[5].attrs.level == null

        when:
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0, targetValue: (short) 30, duration: (short) 2]))
        then:
        kids[5].attrs.level == 30

        when: "finished: value and target agree"
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 30, targetValue: (short) 30, duration: (short) 0]))
        then:
        kids[5].attrs.level == 30
        kids[5].attrs.switch == "on"
    }

    def "dimmer commands: clamping, off at 0, durations, on restores the last level"() {
        given:
        load()

        when:
        kids[5].script.setLevel(100)
        kids[5].script.setLevel(0)
        kids[5].script.setLevel(40, 5)
        kids[5].script.setLevel(40, 300)
        kids[5].script.on()
        kids[5].script.off()

        then:
        hubCommands == ["EP5>SwitchMultilevelSet[dimmingDuration:0, value:99]", // on/off duration (unknown = 0)
                        "EP5>SwitchMultilevelSet[dimmingDuration:0, value:0]",
                        "EP5>SwitchMultilevelSet[dimmingDuration:5, value:40]",
                        "EP5>SwitchMultilevelSet[dimmingDuration:132, value:40]", // 0x7F + 5 minutes
                        "EP5>SwitchMultilevelSet[dimmingDuration:255, value:255]",
                        "EP5>SwitchMultilevelSet[dimmingDuration:255, value:0]"]
        scheduled.count { it == "pollDimmer" } == 6
    }

    def "on, off and setLevel without a duration use the on/off duration preference"() {
        given:
        load([:], [5: [onOffDuration: 130, dimDuration: 7]]) // 130 = 3 minutes

        when:
        kids[5].script.on()
        kids[5].script.off()
        kids[5].script.setLevel(40)
        kids[5].script.setLevel(40, 5)

        then:
        hubCommands == ["EP5>SwitchMultilevelSet[dimmingDuration:130, value:255]", // 0x7F + 3 minutes
                        "EP5>SwitchMultilevelSet[dimmingDuration:130, value:0]",
                        "EP5>SwitchMultilevelSet[dimmingDuration:130, value:40]",
                        "EP5>SwitchMultilevelSet[dimmingDuration:5, value:40]"]
    }

    def "without the preference, setLevel uses the device's on/off duration"() {
        given:
        load()
        parentState.deviceConfig = ["2": 7, "3": 10]

        when:
        kids[5].script.setLevel(40)
        kids[5].script.on()

        then:
        hubCommands == ["EP5>SwitchMultilevelSet[dimmingDuration:10, value:40]",
                        "EP5>SwitchMultilevelSet[dimmingDuration:255, value:255]"] // the device uses parameter 3
    }

    def "dimmer level change uses typed Switch Multilevel v4 commands"() {
        given:
        load()

        when:
        kids[5].script.startLevelChange("down")
        kids[5].script.startLevelChange("up")
        kids[5].script.stopLevelChange()

        then:
        hubCommands == ["EP5>SwitchMultilevelStartLevelChange[dimmingDuration:255, ignoreStartLevel:true, incDec:3, startLevel:0, stepSize:0, upDown:true]",
                        "EP5>SwitchMultilevelStartLevelChange[dimmingDuration:255, ignoreStartLevel:true, incDec:3, startLevel:0, stepSize:0, upDown:false]",
                        "EP5>SwitchMultilevelStopLevelChange"]
    }

    def "reports after a dimmer command are digital, also for the buttons that control the dimmer"() {
        given:
        load()
        kids[5].script.setLevel(50)

        when:
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 50]))
        fromEndpoint(1, report(BasicReport, [value: (short) 0xFF])) // button 1 controls the dimmer by default
        then:
        kids[5].events.last().type == "digital"
        kids[1].events.last().type == "digital"
        buttonEvents().isEmpty()

        when:
        clock += 10_000
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 30]))
        then:
        kids[5].attrs.level == 30
        kids[5].events.last().type == "physical"
    }

    def "a button press during the digital window makes the following reports physical (seen on firmware 1.09)"() {
        given:
        load()
        kids[5].script.setLevel(50) // window: on/off duration (unknown = 0) + 3 s
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 50]))
        clock += 1_000

        when: "button 2 does not control the dimmer: the dimmer window stays"
        scene(2, 0, 7)
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 50]))
        then:
        kids[5].events.last().type == "digital"

        when: "button 1 controls the dimmer and turns it off"
        fromEndpoint(1, report(BasicReport, [value: (short) 0]))
        scene(1, 0, 8)
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0, targetValue: (short) 0, duration: (short) 0]))
        then:
        kids[5].attrs.switch == "off"
        kids[5].events.last().type == "physical"
        buttonEvents() == [[ "pushed", 2], ["pushed", 1]]
    }

    def "levels pass through unchanged with a minimum and maximum set, since the device applies them itself"() {
        given:
        load([:], [5: [minLevel: 30, maxLevel: 80]])

        when:
        kids[5].script.setLevel(1)
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 1]))

        then: "as logged by firmware 1.05: setLevel 1 is reported back as 1"
        hubCommands[0] == "EP5>SwitchMultilevelSet[dimmingDuration:0, value:1]"
        kids[5].attrs.level == 1
    }

    def "without a turn-on level preference, presetLevel is for the next turn-on only and setLevel does not use it up (firmware #fw)"() {
        given:
        load([:], [5: [onOffDuration: 3]])
        kids[5].data.firmwareVersion = fw
        kids[5].attrs.switch = "off"

        when:
        kids[5].script.presetLevel(50)
        then:
        configSets() == presetWrite
        kids[5].attrs.levelPreset == 50
        kids[5].attrs.level == 50
        kids[5].attrs.switch == "off"
        kids[5].settingUpdates.isEmpty()

        when: "setLevel in between writes no parameter and keeps the preset"
        hubCommands.clear()
        kids[5].script.setLevel(30)
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 30])) // digital
        kids[5].script.off()
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0]))
        then:
        configSets().isEmpty()
        kids[5].state.presetLevel == 50

        when: "the next on() uses it, the one after restores the last level"
        hubCommands.clear()
        kids[5].script.on()
        kids[5].script.on()
        then:
        hubCommands == [firstOn, "EP5>SwitchMultilevelSet[dimmingDuration:3, value:255]"]
        kids[5].state.presetLevel == null

        where:
        fw     | presetWrite        | firstOn
        "1.09" | [set(9, 50, 1)]    | "EP5>SwitchMultilevelSet[dimmingDuration:3, value:255]"   // the device applies parameter 9
        "1.05" | []                 | "EP5>SwitchMultilevelSet[dimmingDuration:3, value:50]"    // 1.05 ignores parameter 9
    }

    def "a turn-on by hand uses up a one-off preset"() {
        given:
        load()
        kids[5].data.firmwareVersion = "1.05"
        kids[5].attrs.switch = "off"
        kids[5].script.presetLevel(50)
        clock += 10_000

        when:
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 40])) // physical
        then:
        kids[5].events.last().type == "physical"
        kids[5].state.presetLevel == null

        when:
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0]))
        hubCommands.clear()
        kids[5].script.on()
        then:
        hubCommands == ["EP5>SwitchMultilevelSet[dimmingDuration:255, value:255]"]
    }

    def "with a turn-on level preference, presetLevel changes it, and every turn-on uses it until the next presetLevel"() {
        given:
        load([:], [5: [onLevel: 50]])
        kids[5].data.firmwareVersion = "1.09"
        kids[5].attrs.switch = "off"

        when: "written straight away as 128 + level, and recorded as the desired value"
        kids[5].script.presetLevel(30)
        then:
        configSets() == [set(9, 158 - 256, 1)]
        kids[5].settingUpdates.onLevel == [value: "30", type: "number"]
        parentState.desiredConfig["9"] == [size: 1, value: 158L]
        kids[5].attrs.levelPreset == 30
        kids[5].attrs.switch == "off"
        kids[5].state.presetLevel == null

        when: "setLevel writes no parameter; every on() uses the turn-on level"
        hubCommands.clear()
        kids[5].script.setLevel(70)
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 70]))
        kids[5].script.off()
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0]))
        kids[5].script.on()
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 30]))
        kids[5].script.off()
        fromEndpoint(5, report(SwitchMultilevelReport, [value: (short) 0]))
        kids[5].script.on()
        then:
        configSets().isEmpty()
        hubCommands.findAll { it.startsWith("EP5>SwitchMultilevelSet") } ==
            ["EP5>SwitchMultilevelSet[dimmingDuration:0, value:70]", "EP5>SwitchMultilevelSet[dimmingDuration:255, value:0]",
             "EP5>SwitchMultilevelSet[dimmingDuration:0, value:30]", "EP5>SwitchMultilevelSet[dimmingDuration:255, value:0]",
             "EP5>SwitchMultilevelSet[dimmingDuration:0, value:30]"]
    }

    def "presetLevel while on sets the level now, and with a preference also changes the turn-on level"() {
        given:
        load([:], [5: prefs])
        kids[5].data.firmwareVersion = "1.08"
        kids[5].attrs.switch = "on"

        when:
        kids[5].script.presetLevel(60)
        then:
        hubCommands == expected
        kids[5].state.presetLevel == null

        where:
        prefs         | expected
        [:]           | ["EP5>SwitchMultilevelSet[dimmingDuration:0, value:60]"]
        [onLevel: 50] | [set(9, 188 - 256, 1), "delay 400", "ConfigurationGet[parameterNumber:9]",
                         "EP5>SwitchMultilevelSet[dimmingDuration:0, value:60]"]
    }

    def "a fixed turn-on level of 0 counts as not set"() {
        given:
        load([:], [5: [onLevel: 0]])
        kids[5].data.firmwareVersion = "1.08"
        kids[5].attrs.switch = "off"

        when:
        kids[5].script.updated()
        parent.syncParameters()

        then:
        !configSets().any { it.contains("parameterNumber:9,") }
        !parentState.desiredConfig.containsKey("9")
    }

    def "fixed turn-on level is written as 128 + level, and cleared on the device once it is removed"() {
        given:
        load([:], [5: [onLevel: 50]])
        kids[5].data.firmwareVersion = "1.08"

        when:
        kids[5].script.updated()
        parent.syncParameters()
        then:
        configSets() == [set(9, 178 - 256, 1)]
        kids[5].state.fixedOnLevelSet == true

        when: "the preference is cleared"
        hubCommands.clear()
        loadChild(5, [:])
        kids[5].state.fixedOnLevelSet = true
        kids[5].data.firmwareVersion = "1.08"
        kids[5].script.updated()
        then:
        configSets() == [set(9, 0, 1)]
        !parentState.desiredConfig.containsKey("9")
    }

    def "with firmware before 1.08 a fixed turn-on level is not written to the device, but on() uses it"() {
        given:
        load([:], [5: [onLevel: 50]])
        kids[5].data.firmwareVersion = "1.05"

        when:
        kids[5].script.updated()
        parent.syncParameters()
        kids[5].script.on()

        then:
        configSets().isEmpty()
        hubCommands.contains("EP5>SwitchMultilevelSet[dimmingDuration:0, value:50]")
    }

    // ---------- parameters ----------

    def "button settings are written to the button's own parameters and confirmed by reading back"() {
        given:
        load([:], [2: [buttonMode: "3", timerDuration: 43200]])

        when:
        kids[2].script.updated()
        parent.syncParameters()
        then:
        configSets() == [set(24, 3, 1), set(25, 43200 - 65536, 2)] // 43200 does not fit a signed 2-byte value
        hubCommands.containsAll(["ConfigurationGet[parameterNumber:24]", "ConfigurationGet[parameterNumber:25]"])
        parentAttrs.syncStatus == "2 pending"

        when: "the device reports the values back, as unsigned bytes"
        configReport(25, [0xA8, 0xC0])
        configReport(24, [3])
        then:
        parentState.deviceConfig["25"] == 43200
        parentAttrs.syncStatus == "synced"

        when: "saving again writes nothing"
        hubCommands.clear()
        kids[2].script.updated()
        parent.syncParameters()
        then:
        configSets().isEmpty()
    }

    def "group 4 levels and colours are packed into 4-byte parameters"() {
        given:
        load([:], [4: [group4OnLevel: 80, offColor: "#102030"]])

        when:
        kids[4].script.updated()
        parent.syncParameters()

        then:
        configSets() == [set(47, 0x10203000L, 4), set(42, 0x01500000L, 4)] // button 4: 40 + 7 and 40 + 2
    }

    def "parent settings: only set parameters are written, plus the two driver defaults"() {
        given:
        load([configParam7: "0", configParam13: 80, configParam14: "nonsense"])

        when:
        parent.updated()

        then:
        configSets() == [set(7, 0, 1), set(10, 1, 1), set(13, 80, 1)]
        logs.any { it.startsWith("warn:") && it.contains("'nonsense' is not a colour") }
    }

    def "clearing a setting stops the driver from enforcing it"() {
        given:
        load()
        parentState.desiredConfig = ["25": [size: 2, value: 300]]

        when:
        kids[2].script.updated()

        then:
        !parentState.desiredConfig.containsKey("25")
    }

    def "refreshParameters reads all 47 parameters"() {
        given:
        load()

        when:
        parent.refreshParameters()

        then:
        hubCommands.findAll { it.startsWith("ConfigurationGet") }.size() == 47
    }

    // ---------- migration from 1.x ----------

    def "button settings from 1.x are moved to their new names and still applied"() {
        given:
        load([:], [1: [p16: "1", p17: 600, p19: "2", p20: "5"]])

        when:
        kids[1].script.updated()
        parent.syncParameters()

        then:
        kids[1].settingUpdates.buttonMode == [value: "1", type: "enum"]
        kids[1].settingUpdates.timerDuration == [value: "600", type: "number"]
        kids[1].removed.containsAll(["p16", "p17", "p19", "p20"])
        configSets() == [set(16, 1, 1), set(17, 600, 2), set(19, 2, 1), set(20, 5, 1)]
    }

    def "dimmer settings from 1.x are migrated and the pre-staging parameter is reset"() {
        given:
        load([:], [5: [p1: 5, p5: 10, p6: 100, prestaging: true, prestage_button: 2]])

        when:
        kids[5].script.updated()
        then: "the group 4 levels of button 2 (parameter 26) go back to the device default straight away"
        configSets() == [set(26, 0x01FF0000L, 4)]
        kids[5].settingUpdates.dimmerButtons == [value: "5", type: "enum"]
        kids[5].settingUpdates.maxLevel == [value: "99", type: "number"]
        kids[5].removed.containsAll(["p1", "p5", "p6", "prestaging", "prestage_button"])
        !kids[5].state.containsKey("resetPrestageParam")

        when:
        hubCommands.clear()
        parent.syncParameters()
        then:
        configSets() == [set(1, 5, 1), set(5, 10, 1), set(6, 99, 1)]
    }

    def "parent setting enhancedLedControl from 1.x becomes parameter 10"() {
        given:
        load([enhancedLedControl: false])

        when:
        parent.updated()

        then:
        parentSettingUpdates.configParam10 == [value: "0", type: "enum"]
        parentRemoved.contains("enhancedLedControl")
        configSets().contains(set(10, 0, 1))
    }

    // ---------- associations ----------

    def "setAssociationGroup replaces a group's members"() {
        given:
        load()

        when:
        parent.setAssociationGroup(3, "1A, 2b-1")

        then:
        hubCommands == ["8E0403", "delay 400",
                        "AssociationSet[groupingIdentifier:3, nodeId:[26]]", "delay 400",
                        "MultiChannelAssociationSet[groupingIdentifier:3, multiChannelNodeIds:[[nodeId:43, bitAddress:0, endPointId:1]]]", "delay 400",
                        "MultiChannelAssociationGet[groupingIdentifier:3]"]
    }

    def "setAssociationGroup with no members clears the group"() {
        given:
        load()

        when:
        parent.setAssociationGroup(14, "")

        then:
        hubCommands == ["8E040E", "delay 400", "MultiChannelAssociationGet[groupingIdentifier:14]"]
    }

    def "setAssociationGroup rejects bad input without sending anything"() {
        given:
        load()

        when:
        parent.setAssociationGroup(group, nodes)

        then:
        hubCommands.isEmpty()
        logs.any { it.startsWith("warn:") && it.contains(message) }

        where:
        group | nodes         || message
        1     | "1A"          || "must be 2-14"
        15    | "1A"          || "must be 2-14"
        3     | "XYZ"         || "invalid association member 'XYZ'"
        3     | "1,2,3,4,5,6" || "at most 5 members"
    }

    def "association reports are stored, and a lifeline without the hub as endpoint 0 gives a warning"() {
        given:
        load()

        when:
        parent.zwaveEvent(report(MultiChannelAssociationReport, [groupingIdentifier: (short) 1, nodeId: [(short) 1], multiChannelNodeIds: []]), 0)
        then:
        parentData.associationGroup1 == "01"
        logs.any { it.startsWith("warn:") && it.contains("Repair Lifeline") }

        when:
        logs.clear()
        parent.zwaveEvent(report(MultiChannelAssociationReport, [groupingIdentifier: (short) 1, nodeId: [],
                                                                 multiChannelNodeIds: [[nodeId: 1, bitAddress: 0, endPointId: 0]]]), 0)
        then:
        parentData.associationGroup1 == "01-0"
        !logs.any { it.startsWith("warn:") }
    }

    def "repairLifeline swaps the hub's plain lifeline association for a multi-channel one"() {
        given:
        load()

        when:
        parent.repairLifeline()

        then:
        hubCommands == ["AssociationRemove[groupingIdentifier:1, nodeId:[1]]", "delay 400",
                        "MultiChannelAssociationSet[groupingIdentifier:1, multiChannelNodeIds:[[nodeId:1, bitAddress:0, endPointId:0]]]", "delay 400",
                        "MultiChannelAssociationGet[groupingIdentifier:1]"]
    }

    // ---------- other reports ----------

    def "Supervision Get is answered every time but its content is processed once"() {
        given:
        load()
        def get = Stub(SupervisionGet) {
            getSessionID() >> (short) 7
            encapsulatedCommand(_ as Map) >> report(BasicReport, [value: (short) 0xFF])
        }

        when:
        parent.zwaveEvent(get, 3)
        parent.zwaveEvent(get, 3)

        then:
        kids[3].attrs.switch == "on"
        buttonEvents() == [["pushed", 3]]
        hubCommands.count { it == "EP3>SupervisionReport[duration:0, moreStatusUpdates:false, reserved:0, sessionID:7, status:255]" } == 2
    }

    def "version and manufacturer reports are stored"() {
        given:
        load()

        when:
        parent.zwaveEvent(report(VersionReport, [applicationVersion: (short) 1, applicationSubVersion: (short) 8]), 0)
        parent.zwaveEvent(report(ManufacturerSpecificReport, [manufacturerId: 0x234, productTypeId: 3, productId: 0x121]), 0)

        then:
        parentData.firmwareVersion == "1.08"
        kids.values().every { it.data.firmwareVersion == "1.08" } // the dimmer needs it for parameter 9
        parentData.MSR == "0234-0003-0121"
    }

    def "a firmware upgrade re-applies the children's settings and detects single-press scenes again"() {
        given: "a fixed turn-on level that firmware 1.05 could not use"
        load([:], [5: [onLevel: 50]])
        parentData.firmwareVersion = "1.05"
        kids.values().each { it.data.firmwareVersion = "1.05" }
        parentState.singlePressScenes = true

        when: "configure after the upgrade reads the new version"
        parent.zwaveEvent(report(VersionReport, [applicationVersion: (short) 1, applicationSubVersion: (short) 9]), 0)
        parent.syncParameters()

        then:
        kids[5].data.firmwareVersion == "1.09"
        configSets() == [set(9, 178 - 256, 1)]
        kids[5].state.fixedOnLevelSet == true
        !parentState.containsKey("singlePressScenes")
        logs.contains("info: ZDB: firmware 1.09 (was 1.05)")

        when: "the same version again changes nothing"
        configReport(9, [178]) // the device confirms parameter 9
        hubCommands.clear()
        parent.zwaveEvent(report(VersionReport, [applicationVersion: (short) 1, applicationSubVersion: (short) 9]), 0)
        parent.syncParameters()
        then:
        configSets().isEmpty()
    }

    def "refresh reads every endpoint"() {
        given:
        load()

        when:
        parent.refresh()

        then:
        hubCommands.findAll { !it.startsWith("delay") } == ["EP1>SwitchBinaryGet", "EP2>SwitchBinaryGet", "EP3>SwitchBinaryGet",
                                                             "EP4>SwitchBinaryGet", "EP5>SwitchMultilevelGet"]
    }
}

/** Stands in for zwave.<commandClass>; every factory call is checked against the API mirror. */
class CommandClassFactory {
    String name
    Closure make

    def methodMissing(String factory, args) {
        make(name, factory, args as Object[])
    }
}
