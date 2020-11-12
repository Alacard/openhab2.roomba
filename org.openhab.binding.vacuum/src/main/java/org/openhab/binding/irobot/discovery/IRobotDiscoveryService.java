package org.openhab.binding.irobot.discovery;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.net.NetUtil;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.irobot.IRobotBindingConstants;
import org.openhab.binding.irobot.internal.IdentProtocol;
import org.openhab.binding.irobot.internal.IdentProtocol.IdentData;
import org.openhab.binding.irobot.roomba.RoombaConfiguration;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParseException;

@Component(service = DiscoveryService.class)
public class IRobotDiscoveryService extends AbstractDiscoveryService {

    private final static Logger logger = LoggerFactory.getLogger(IRobotDiscoveryService.class);
    private final Runnable scanner;
    private ScheduledFuture<?> backgroundFuture;

    public IRobotDiscoveryService() {
        super(Collections.singleton(IRobotBindingConstants.THING_TYPE_ROOMBA), 30, true);
        scanner = createScanner();
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.trace("Starting background discovery");
        stopBackgroundScan();
        backgroundFuture = scheduler.scheduleWithFixedDelay(scanner, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    protected void stopBackgroundDiscovery() {
        logger.trace("Stopping background discovery");
        stopBackgroundScan();
        super.stopBackgroundDiscovery();
    }

    private void stopBackgroundScan() {
        if (backgroundFuture != null && !backgroundFuture.isDone()) {
            backgroundFuture.cancel(true);
            backgroundFuture = null;
        }
    }

    @Override
    protected void startScan() {
        logger.trace("startScan");
        scheduler.execute(scanner);
    }

    private Runnable createScanner() {
        return () -> {
            long timestampOfLastScan = getTimestampOfLastScan();
            for (InetAddress broadcastAddress : getBroadcastAddresses()) {
                logger.debug("Starting broadcast for {}", broadcastAddress.toString());

                DatagramSocket socket;
                try {
                    socket = IdentProtocol.sendRequest(broadcastAddress);
                    while (receivePacketAndDiscover(socket)) {
                    }
                } catch (Exception e) {
                    logger.debug("Error sending broadcast: {}", e.toString());
                }
            }

            removeOlderResults(timestampOfLastScan);
        };
    }

    private List<InetAddress> getBroadcastAddresses() {
        ArrayList<InetAddress> addresses = new ArrayList<>();

        for (String broadcastAddress : NetUtil.getAllBroadcastAddresses()) {
            try {
                addresses.add(InetAddress.getByName(broadcastAddress));
            } catch (UnknownHostException e) {
                logger.warn("Error broadcasting to {}", broadcastAddress, e);
            }
        }

        return addresses;
    }

    private boolean receivePacketAndDiscover(DatagramSocket socket) {
        DatagramPacket incomingPacket;

        try {
            incomingPacket = IdentProtocol.receiveResponse(socket);
        } catch (Exception e) {
            // This is not really an error, eventually we get a timeout
            // due to a loop in the caller
            return false;
        }

        String host = incomingPacket.getAddress().toString().substring(1);

        logger.debug("Received reply from {}", host);
        logger.trace(new String(incomingPacket.getData()));

        IdentProtocol.IdentData ident;

        try {
            ident = IdentProtocol.decodeResponse(incomingPacket);
        } catch (JsonParseException e) {
            logger.error("Malformed JSON reply!");
            return true;
        }

        // This check comes from Roomba980-Python
        if (ident.ver < IdentData.MIN_SUPPORTED_VERSION) {
            logger.info("Found unsupported iRobot \"{}\" version {} at {}", ident.robotname, ident.ver, host);
            return true;
        }

        if (ident.product.equals(IdentData.PRODUCT_ROOMBA)) {
            ThingUID thingUID = new ThingUID(IRobotBindingConstants.THING_TYPE_ROOMBA, host.replace('.', '_'));
            DiscoveryResult result = DiscoveryResultBuilder.create(thingUID)
                    .withProperty(RoombaConfiguration.FIELD_IPADDRESS, host).withLabel("iRobot " + ident.robotname)
                    .build();

            thingDiscovered(result);
        }

        return true;
    }
}
