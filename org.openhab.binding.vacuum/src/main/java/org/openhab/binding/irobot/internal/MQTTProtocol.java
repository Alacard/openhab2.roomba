package org.openhab.binding.irobot.internal;

public class MQTTProtocol {

    public interface Request {
        public String getTopic();
    }

    public static class CommandRequest implements Request {
        public String command;
        public long time;
        public String initiator;

        public CommandRequest(String cmd) {
            command = cmd;
            time = System.currentTimeMillis() / 1000;
            initiator = "localApp";
        }

        @Override
        public String getTopic() {
            return "cmd";
        }
    }

    public static class DeltaRequest implements Request {
        public StateValue state;

        public DeltaRequest(StateValue state) {
            this.state = state;
        }

        @Override
        public String getTopic() {
            return "delta";
        }
    }

    public static class StateValue {
        // Just some common type, nothing to do here
        protected StateValue() {

        }
    }

    public static class OpenOnly extends StateValue {
        public boolean openOnly;

        public OpenOnly(boolean openOnly) {
            this.openOnly = openOnly;
        }
    }

    public static class BinPause extends StateValue {
        public boolean binPause;

        public BinPause(boolean binPause) {
            this.binPause = binPause;
        }
    }

    public static class PowerBoost extends StateValue {
        public boolean carpetBoost;
        public boolean vacHigh;

        public PowerBoost(boolean carpetBoost, boolean vacHigh) {
            this.carpetBoost = carpetBoost;
            this.vacHigh = vacHigh;
        }
    }

    public static class CleanPasses extends StateValue {
        public boolean noAutoPasses;
        public boolean twoPass;

        public CleanPasses(boolean noAutoPasses, boolean twoPass) {
            this.noAutoPasses = noAutoPasses;
            this.twoPass = twoPass;
        }
    }

    public static class Schedule {
        public String[] cycle;
        public int[] h;
        public int[] m;

        public static final int NUM_WEEK_DAYS = 7;

        public Schedule(int cycles_bitmask) {
            cycle = new String[NUM_WEEK_DAYS];
            for (int i = 0; i < NUM_WEEK_DAYS; i++) {
                enableCycle(i, (cycles_bitmask & (1 << i)) != 0);
            }
        }

        public Schedule(String[] cycle) {
            this.cycle = cycle;
        }

        public boolean cycleEnabled(int i) {
            return cycle[i].equals("start");
        }

        public void enableCycle(int i, boolean enable) {
            cycle[i] = enable ? "start" : "none";
        }
    }

    public static class CleanSchedule extends StateValue {
        public Schedule cleanSchedule;

        public CleanSchedule(Schedule schedule) {
            cleanSchedule = schedule;
        }
    }
};
