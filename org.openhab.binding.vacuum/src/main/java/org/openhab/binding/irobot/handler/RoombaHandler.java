/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.irobot.handler;

import static org.openhab.binding.irobot.IRobotBindingConstants.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.irobot.internal.IdentProtocol;
import org.openhab.binding.irobot.internal.IdentProtocol.IdentData;
import org.openhab.binding.irobot.internal.RawMQTT;
import org.openhab.binding.irobot.roomba.RoombaConfiguration;
import org.openhab.binding.irobot.roomba.RoombaMqttBrokerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The {@link RoombaHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author hkuhn42 - Initial contribution
 */
public class RoombaHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(RoombaHandler.class);
    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private static final int reconnectDelay = 5; // In seconds
    private @Nullable Future<?> reconnectReq;
    private RoombaConfiguration config;
    private String blid = null;
    protected RoombaMqttBrokerConnection connection;
    private Hashtable<String, State> lastState = new Hashtable<String, State>();
    private JsonObject lastSchedule = null;
    private boolean auto_passes = true;
    private Boolean two_passes = null;
    private boolean carpet_boost = true;
    private Boolean vac_high = null;
    private boolean isPaused = false;

    public RoombaHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.trace("initialize()");
        config = getConfigAs(RoombaConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);
        connect();
    }

    @Override
    public void dispose() {
        logger.trace("dispose()");

        singleThread.execute(() -> {
            if (reconnectReq != null) {
                reconnectReq.cancel(false);
                reconnectReq = null;
            }

            if (connection != null) {
                connection.stop();
                connection = null;
            }
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String ch = channelUID.getId();
        if (command instanceof RefreshType) {
            State value = lastState.get(ch);

            if (value != null) {
                updateState(ch, value);
            }

            return;
        }

        if (ch.equals(CHANNEL_COMMAND)) {
            if (command instanceof StringType) {
                String cmd = command.toString();

                if (cmd.equals(CMD_CLEAN)) {
                    cmd = isPaused ? "resume" : "start";
                }

                JsonObject request = new JsonObject();

                request.addProperty("command", cmd);
                request.addProperty("time", System.currentTimeMillis() / 1000);
                request.addProperty("initiator", "localApp");

                sendRequest("cmd", request);
            }
        } else if (ch.startsWith(CHANNEL_SCHED_SWITCH_PREFIX)) {
            JsonObject schedule = lastSchedule;

            // Schedule can only be updated in a bulk, so we have to store current
            // schedule and modify components.
            if (command instanceof OnOffType && schedule != null && schedule.has("cycle")) {
                for (int i = 0; i < CHANNEL_SCHED_SWITCH.length; i++) {
                    if (ch.equals(CHANNEL_SCHED_SWITCH[i])) {
                        JsonArray cycle = schedule.getAsJsonArray("cycle");

                        enableCycle(cycle, i, command.equals(OnOffType.ON));
                        sendSchedule(schedule);
                        break;
                    }
                }
            }
        } else if (ch.equals(CHANNEL_SCHEDULE)) {
            if (command instanceof DecimalType) {
                int bitmask = ((DecimalType) command).intValue();
                JsonArray cycle = new JsonArray(CHANNEL_SCHED_SWITCH.length);

                for (int i = 0; i < CHANNEL_SCHED_SWITCH.length; i++) {
                    enableCycle(cycle, i, (bitmask & (1 << i)) != 0);
                }

                JsonObject schedule = new JsonObject();
                schedule.add("cycle", cycle);
                sendSchedule(schedule);
            }
        } else if (ch.equals(CHANNEL_EDGE_CLEAN)) {
            if (command instanceof OnOffType) {
                JsonObject state = new JsonObject();
                state.addProperty("openOnly", command.equals(OnOffType.OFF));
                sendDelta(state);
            }
        } else if (ch.equals(CHANNEL_ALWAYS_FINISH)) {
            if (command instanceof OnOffType) {
                JsonObject state = new JsonObject();
                state.addProperty("binPause", command.equals(OnOffType.OFF));
                sendDelta(state);
            }
        } else if (ch.equals(CHANNEL_POWER_BOOST)) {
            if (command instanceof StringType) {
                String cmd = command.toString();
                JsonObject state = new JsonObject();
                state.addProperty("carpetBoost", cmd.equals(BOOST_AUTO));
                state.addProperty("vacHigh", cmd.equals(BOOST_PERFORMANCE));
                sendDelta(state);
            }
        } else if (ch.equals(CHANNEL_CLEAN_PASSES)) {
            if (command instanceof StringType) {
                String cmd = command.toString();
                JsonObject state = new JsonObject();
                state.addProperty("noAutoPasses", !cmd.equals(PASSES_AUTO));
                state.addProperty("twoPass", cmd.equals(PASSES_2));
                sendDelta(state);
            }
        }
    }

    private void enableCycle(JsonArray cycle, int i, boolean enable) {
        JsonPrimitive value = new JsonPrimitive(enable ? "start" : "none");
        cycle.set(i, value);
    }

    private void sendSchedule(JsonObject schedule) {
        JsonObject state = new JsonObject();
        state.add("cleanSchedule", schedule);
        sendDelta(state);
    }

    private void sendDelta(JsonObject state) {
        // Huge thanks to Dorita980 author(s) for an insight on this
        JsonObject request = new JsonObject();
        request.add("state", state);

        logger.trace("Sending delta: {}", request.toString());
        sendRequest("delta", request);
    }

    private void sendRequest(String topic, JsonObject request) {
        connection.publish(topic, request.toString().getBytes());
    }

    private void connect() {
        // In order not to mess up our connection state we need to make sure
        // that any two calls are never running concurrently. We use
        // singleThreadExecutorService for this purpose
        singleThread.execute(() -> {
            String error = null;

            logger.info("Connecting to {}", config.ipaddress);

            try {
                InetAddress host = InetAddress.getByName(config.ipaddress);

                if (blid == null) {
                    DatagramSocket identSocket = IdentProtocol.sendRequest(host);
                    DatagramPacket identPacket = IdentProtocol.receiveResponse(identSocket);

                    identSocket.close();
                    IdentProtocol.IdentData ident;

                    try {
                        ident = new IdentProtocol.IdentData(identPacket);
                    } catch (JsonParseException | ClassCastException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Malformed IDENT response");
                        return;
                    }

                    if (ident.ver < IdentData.MIN_SUPPORTED_VERSION) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Unsupported version " + ident.ver);
                        return;
                    }

                    if (!ident.product.equals(IdentData.PRODUCT_ROOMBA)) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Not a Roomba: " + ident.product);
                        return;
                    }

                    blid = ident.blid;
                }

                logger.debug("BLID is: {}", blid);

                if (!config.havePassword()) {
                    RawMQTT mqtt;

                    try {
                        mqtt = new RawMQTT(host, 8883);
                    } catch (KeyManagementException | NoSuchAlgorithmException e1) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e1.toString());
                        return; // This is internal system error, no retry
                    }

                    mqtt.requestPassword();
                    RawMQTT.Packet response = mqtt.readPacket();
                    mqtt.close();

                    if (response != null && response.isValidPasswdPacket()) {
                        RawMQTT.PasswdPacket passwdPacket = new RawMQTT.PasswdPacket(response);

                        config.password = passwdPacket.getPassword();
                        if (config.havePassword()) {
                            Configuration configuration = editConfiguration();

                            configuration.put(RoombaConfiguration.FIELD_PASSWORD, config.password);
                            updateConfiguration(configuration);
                        }
                    }
                }

                if (!config.havePassword()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "Authentication on the robot is required");
                    scheduleReconnect();
                    return;
                }

                logger.debug("Password is: " + config.password);

                // BLID is used as both client ID and username. The name of BLID also came from Roomba980-python
                connection = new RoombaMqttBrokerConnection(config.ipaddress, blid, this);
                connection.start(blid, config.password);

            } catch (IOException e) {
                error = e.toString();
            }

            if (error != null) {
                logger.error(error);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, error);
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        reconnectReq = scheduler.schedule(() -> {
            connect();
        }, reconnectDelay, TimeUnit.SECONDS);
    }

    public void onConnected() {
        updateStatus(ThingStatus.ONLINE);
    }

    public void onDisconnected(Throwable error) {
        String message = error.getMessage();

        logger.error("MQTT connection failed: {}", message);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        scheduleReconnect();
    }

    public void processMessage(String topic, byte[] payload) {
        String jsonStr = new String(payload);
        logger.trace("Got topic {} data {}", topic, jsonStr);

        try {
            // Data comes as JSON string: {"state":{"reported":<Actual content here>}}
            // or: {"state":{"desired":<Some content here>}}
            // Of the second form i've so far observed only: {"state":{"desired":{"echo":null}}}
            // I don't know what it is, so let's ignore it.
            // Examples of the first form are given below, near the respective parsing code
            JsonParser parser = new JsonParser();
            JsonObject state = parser.parse(jsonStr).getAsJsonObject().getAsJsonObject("state");

            if (!state.has("reported")) {
                return;
            }

            JsonObject reported = state.getAsJsonObject("reported");

            if (reported.has("cleanMissionStatus")) {
                // {"cleanMissionStatus":{"cycle":"clean","phase":"hmUsrDock","expireM":0,"rechrgM":0,"error":0,"notReady":0,"mssnM":1,"sqft":7,"initiator":"rmtApp","nMssn":39}}
                JsonObject missionStatus = reported.getAsJsonObject("cleanMissionStatus");
                String cycle = missionStatus.get("cycle").getAsString();
                String phase = missionStatus.get("phase").getAsString();
                String command;

                if (cycle.equals("none")) {
                    command = CMD_STOP;
                } else {
                    switch (phase) {
                        case "stop":
                        case "stuck": // CHECKME: could also be equivalent to "stop" command
                        case "pause": // Never observed in Roomba 930
                            command = CMD_PAUSE;
                            break;
                        case "hmUsrDock":
                        case "dock": // Never observed in Roomba 930
                            command = CMD_DOCK;
                            break;
                        default:
                            command = cycle; // "clean" or "spot"
                            break;
                    }
                }

                isPaused = command.equals(CMD_PAUSE);

                reportString(CHANNEL_CYCLE, cycle);
                reportString(CHANNEL_PHASE, phase);
                reportString(CHANNEL_COMMAND, command);
                reportString(CHANNEL_ERROR, String.valueOf(missionStatus.get("error").getAsInt()));
            }

            if (reported.has("batPct")) {
                reportInt(CHANNEL_BATTERY, reported.get("batPct").getAsInt());
            }

            if (reported.has("bin")) {
                JsonObject bin = reported.getAsJsonObject("bin");
                String binStatus;

                // The bin cannot be both full and removed simultaneously, so let's
                // encode it as a single value
                if (!bin.get("present").getAsBoolean()) {
                    binStatus = BIN_REMOVED;
                } else if (bin.get("full").getAsBoolean()) {
                    binStatus = BIN_FULL;
                } else {
                    binStatus = BIN_OK;
                }

                reportString(CHANNEL_BIN, binStatus);
            }

            if (reported.has("signal")) {
                // {"signal":{"rssi":-55,"snr":33}}
                JsonObject signal = reported.getAsJsonObject("signal");

                reportInt(CHANNEL_RSSI, signal.get("rssi").getAsInt());
                reportInt(CHANNEL_SNR, signal.get("snr").getAsInt());
            }

            if (reported.has("cleanSchedule")) {
                // "cleanSchedule":{"cycle":["none","start","start","start","start","none","none"],"h":[9,12,12,12,12,12,9],"m":[0,0,0,0,0,0,0]}
                JsonObject schedule = reported.getAsJsonObject("cleanSchedule");

                if (schedule.has("cycle")) {
                    JsonArray cycle = schedule.getAsJsonArray("cycle");
                    int binary = 0;

                    for (int i = 0; i < cycle.size(); i++) {
                        boolean on = cycle.get(i).getAsString().equals("start");

                        reportSwitch(CHANNEL_SCHED_SWITCH[i], on);
                        if (on) {
                            binary |= (1 << i);
                        }
                    }

                    reportInt(CHANNEL_SCHEDULE, binary);
                }

                lastSchedule = schedule;
            }

            if (reported.has("openOnly")) {
                // "openOnly":false
                reportSwitch(CHANNEL_EDGE_CLEAN, !reported.get("openOnly").getAsBoolean());
            }

            if (reported.has("binPause")) {
                // "binPause":true
                reportSwitch(CHANNEL_ALWAYS_FINISH, !reported.get("binPause").getAsBoolean());
            }

            if (reported.has("carpetBoost")) {
                // "carpetBoost":true
                carpet_boost = reported.get("carpetBoost").getAsBoolean();
                if (carpet_boost) {
                    // When set to true, overrides vacHigh
                    reportString(CHANNEL_POWER_BOOST, BOOST_AUTO);
                } else if (vac_high != null) {
                    reportVacHigh();
                }
            }

            if (reported.has("vacHigh")) {
                // "vacHigh":false
                vac_high = reported.get("vacHigh").getAsBoolean();
                if (!carpet_boost) {
                    // Can be overridden by "carpetBoost":true
                    reportVacHigh();
                }
            }

            if (reported.has("noAutoPasses")) {
                // "noAutoPasses":true
                auto_passes = !reported.get("noAutoPasses").getAsBoolean();
                if (auto_passes) {
                    // When set to false, overrides twoPass
                    reportString(CHANNEL_CLEAN_PASSES, PASSES_AUTO);
                } else if (two_passes != null) {
                    reportTwoPasses();
                }
            }

            if (reported.has("twoPass")) {
                // "twoPass":true
                two_passes = reported.get("twoPass").getAsBoolean();
                if (!auto_passes) {
                    // Can be overridden by "noAutoPasses":false
                    reportTwoPasses();
                }
            }

            // {"navSwVer":"01.12.01#1","wifiSwVer":"20992","mobilityVer":"5806","bootloaderVer":"4042","umiVer":"6","softwareVer":"v2.4.6-3","tz":{"events":[{"dt":1583082000,"off":180},{"dt":1619884800,"off":180},{"dt":0,"off":0}],"ver":8}}
            reportProperty(Thing.PROPERTY_FIRMWARE_VERSION, reported, "softwareVer");
            reportProperty(reported, "navSwVer");
            reportProperty(reported, "wifiSwVer");
            reportProperty(reported, "mobilityVer");
            reportProperty(reported, "bootloaderVer");
            reportProperty(reported, "umiVer");
        } catch (JsonParseException | ClassCastException e) {
            logger.error("Failed to parse JSON message from {}: {}", config.ipaddress, e);
            logger.error("Raw contents: {}", payload);
        }
    }

    private void reportVacHigh() {
        reportString(CHANNEL_POWER_BOOST, vac_high ? BOOST_PERFORMANCE : BOOST_ECO);
    }

    private void reportTwoPasses() {
        reportString(CHANNEL_CLEAN_PASSES, two_passes ? PASSES_2 : PASSES_1);
    }

    private void reportString(String channel, String str) {
        reportState(channel, StringType.valueOf(str));
    }

    private void reportInt(String channel, int n) {
        reportState(channel, new DecimalType(n));
    }

    private void reportSwitch(String channel, boolean s) {
        reportState(channel, OnOffType.from(s));
    }

    private void reportState(String channel, State value) {
        lastState.put(channel, value);
        updateState(channel, value);
    }

    private void reportProperty(JsonObject container, String attribute) {
        reportProperty(attribute, container, attribute);
    }

    private void reportProperty(String property, JsonObject reported, String attribute) {
        if (reported.has(attribute)) {
            updateProperty(property, reported.get(attribute).getAsString());
        }
    }
}
