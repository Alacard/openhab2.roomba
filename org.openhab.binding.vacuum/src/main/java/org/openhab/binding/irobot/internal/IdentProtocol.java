package org.openhab.binding.irobot.internal;

import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

public class IdentProtocol {

    private static final String UDP_PACKET_CONTENTS = "irobotmcs";
    private static final int REMOTE_UDP_PORT = 5678;

    public static DatagramSocket sendRequest(InetAddress host) throws IOException {
        DatagramSocket socket = new DatagramSocket();

        socket.setBroadcast(true);
        socket.setReuseAddress(true);

        byte[] packetContents = UDP_PACKET_CONTENTS.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(packetContents, packetContents.length, host, REMOTE_UDP_PORT);

        socket.send(packet);
        return socket;
    }

    public static DatagramPacket receiveResponse(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[1024];
        DatagramPacket incomingPacket = new DatagramPacket(buffer, buffer.length);

        socket.setSoTimeout(1000 /* one second */);
        socket.receive(incomingPacket);

        return incomingPacket;
    }

    public static class IdentData {
        public static int MIN_SUPPORTED_VERSION = 2;
        public static String PRODUCT_ROOMBA = "Roomba";

        public int ver;
        public String product;
        public String blid;
        public String robotname;

        public IdentData(DatagramPacket incomingPacket) throws JsonParseException, ClassCastException {
            /*
             * incomingPacket is a JSON of the following contents (addresses are undisclosed):
             * @formatter:off
             * {
             *   "ver":"3",
             *   "hostname":"Roomba-3168820480607740",
             *   "robotname":"Roomba",
             *   "ip":"XXX.XXX.XXX.XXX",
             *   "mac":"XX:XX:XX:XX:XX:XX",
             *   "sw":"v2.4.6-3",
             *   "sku":"R981040",
             *   "nc":0,
             *   "proto":"mqtt",
             *   "cap":{
             *     "pose":1,
             *     "ota":2,
             *     "multiPass":2,
             *     "carpetBoost":1,
             *     "pp":1,
             *     "binFullDetect":1,
             *     "langOta":1,
             *     "maps":1,
             *     "edge":1,
             *     "eco":1,
             *     "svcConf":1
             *   }
             * }
             * @formatter:on
             */
            String reply = new String(incomingPacket.getData());
            JsonParser parser = new JsonParser();
            JsonReader jsonReader = new JsonReader(new StringReader(reply));
            // GSON bug? The JSON above doesn't look malformed.
            // Does it choke on "mac" field, containing ':' inside the string ?
            jsonReader.setLenient(true);
            JsonObject irobotInfo = parser.parse(jsonReader).getAsJsonObject();

            ver = irobotInfo.get("ver").getAsInt();
            robotname = irobotInfo.get("robotname").getAsString();

            String[] hostname = irobotInfo.get("hostname").getAsString().split("-");

            // This also comes from Roomba980-Python. Comments there say that "iRobot"
            // prefix is used by i7. We assume for other robots it would be product
            // name, e. g. "Scooba"
            if (hostname[0].equals("iRobot")) {
                product = "Roomba";
            } else {
                product = hostname[0];
            }

            blid = hostname[1];
        }
    }
}
