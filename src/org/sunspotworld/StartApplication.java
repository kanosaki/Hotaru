/*
 * Copyright (c) 2006-2008 Sun Microsystems, Inc.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to 
 * deal in the Software without restriction, including without limitation the 
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or 
 * sell copies of the Software, and to permit persons to whom the Software is 
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER 
 * DEALINGS IN THE SOFTWARE.
 */
package org.sunspotworld;

import com.sun.spot.sensorboard.EDemoBoard;
import com.sun.spot.sensorboard.peripheral.ITriColorLED;
import com.sun.spot.sensorboard.peripheral.LEDColor;
import com.sun.spot.peripheral.radio.*;
import com.sun.spot.peripheral.Spot;
import com.sun.spot.peripheral.ILed;
import com.sun.spot.sensorboard.peripheral.ISwitch;
import com.sun.spot.sensorboard.peripheral.ILightSensor;

import com.sun.spot.io.j2me.radiogram.*;
import com.sun.spot.peripheral.TimeoutException;

import com.sun.spot.util.IEEEAddress;
import java.io.*;
import java.util.Calendar;
import javax.microedition.io.*;

import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

public class StartApplication extends MIDlet {

    // ログ関連定数
    private static final int DEBUG = 0;
    private static final int INFO = 1;
    private static final int WARN = 2;
    private static final int ERROR = 3;
    /**
     * どれくらいログを細かく出すかのしきい値。
     */
    private int logLevel = 1;
    /**
     * アプリケーションのバージョン
     */
    private static final String VERSION = "1.0";
    private static final int INITIAL_CHANNEL_NUMBER = IProprietaryRadio.DEFAULT_CHANNEL;
    private static final short PAN_ID = IRadioPolicyManager.DEFAULT_PAN_ID;
    //private static final String BROADCAST_PORT      = "42";
    private static final String BROADCAST_PORT = "76";
    private static final byte PACKET_MAGIC_NUMBER = 0x56;
    private static final int BLINK_INTERVAL = 1000;
    private static final int BOOST_LED_THRESHOLD = 600;
    private static final int MAX_BOOST_LED_THRESHOLD = 725;
    private ISwitch sw1 = EDemoBoard.getInstance().getSwitches()[EDemoBoard.SW1];
    private ISwitch sw2 = EDemoBoard.getInstance().getSwitches()[EDemoBoard.SW2];
    private ITriColorLED leds[] = EDemoBoard.getInstance().getLEDs();
    private ITriColorLED statusLED = leds[0];
    private ILightSensor light = EDemoBoard.getInstance().getLightSensor();
    private LEDColor red = new LEDColor(50, 0, 0);
    private LEDColor green = new LEDColor(0, 20, 0);
    private LEDColor blue = new LEDColor(0, 0, 50);
    private LEDColor white = new LEDColor(255, 255, 255);
    private int channel = INITIAL_CHANNEL_NUMBER;
    private int power = 32;                             // Start with max transmit power
    private boolean xmitDo = true;
    private boolean recvDo = true;
    private boolean ledsInUse = false;
    private boolean boostLEDs = false;
    private boolean maxBoostLEDs = false;
    private Strategy currentStrategy;
    private int hotaruState;

    /**
     * 動作を制御するStrategyを変更します
     * 現在動いているStrategyが存在する場合は、haltされます。.....
     */
    private void setStrategy(Strategy s) {
        if (s != currentStrategy) {
            if (currentStrategy != null) {
                log(INFO, "Stopping: " + currentStrategy.toString());
                currentStrategy.halt();
            }
            if (s != null) {
                log(INFO, "New Strategy:");
                s.printInfo();
                setStatusLed(s.getStatusColor(), true);
            } else {
                log(INFO, "Paused");
                setStatusLed(LEDColor.RED, true);
            }
            currentStrategy = s;
        }
    }

    /**
     * Return bright or dim red.
     *
     * @returns appropriately bright red LED settings
     */
    private LEDColor getRed() {
        return boostLEDs ? LEDColor.RED : red;
    }

    /**
     * Return bright or dim green.
     *
     * @returns appropriately bright green LED settings
     */
    private LEDColor getGreen() {
        return boostLEDs ? LEDColor.GREEN : green;
    }

    /**
     * Return bright or dim blue.
     *
     * @returns appropriately bright blue LED settings
     */
    private LEDColor getBlue() {
        return maxBoostLEDs ? white : boostLEDs ? LEDColor.BLUE : blue;
    }

    public void log(int level, String msg) {
        if (level < logLevel) {
            return;
        }
        String levelStr = null;
        switch (level) {
            case 0:
                levelStr = "DEBUG";
                break;
            case 1:
                levelStr = "INFO";
                break;
            case 2:
                levelStr = "WARN";
                break;
            case 3:
                levelStr = "ERROR";
                break;
            default:
                levelStr = "Level(" + Integer.toString(level) + ")";
                break;
        }
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int msec = cal.get(Calendar.MILLISECOND);
        String ts = "[" + hour + ":" + minute + ":" + second + "." + msec + "] " + levelStr + " ";
        System.out.println(ts + " " + msg);
    }

    /**
     * Check if in really bright environment.
     *
     * @returns true if it's really bright, false if not so bright
     */
    private void checkLightSensor() {
        try {
            int val = light.getValue();
            boostLEDs = (val > BOOST_LED_THRESHOLD);
            maxBoostLEDs = (val > MAX_BOOST_LED_THRESHOLD);
        } catch (IOException ex) {
        }
    }

    /**
     * Pause for a specified time.
     *
     * @param time the number of milliseconds to pause
     */
    private void pause(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException ex) { /* ignore */ }
    }

    /**
     * Initialize any needed variables.
     */
    private void initialize() {
        checkLightSensor();
        IRadioPolicyManager rpm = Spot.getInstance().getRadioPolicyManager();
        rpm.setChannelNumber(channel);
        rpm.setPanId(PAN_ID);
        rpm.setOutputPower(power - 32);
        setStrategy(new MasterStrategy(BLINK_INTERVAL));
        hotaruState = 0;
    }

    private void setStatusLed(LEDColor color, boolean state) {
        statusLED.setColor(color);
        statusLED.setOn(state);
    }

    /**
     * Main application run loop.
     */
    private void run() {
        new Thread() {

            public void run() {
                xmitLoop();
            }
        }.start();                      // spawn a thread to transmit packets
        new Thread() {

            public void run() {
                recvLoop();
            }
        }.start();                      // spawn a thread to receive packets
        respondToSwitches();            // this thread will handle User input via switches
    }

    /**
     * Display a number (base 2) in LEDs 1-7
     *
     * @param val the number to display
     * @param col the color to display in LEDs
     */
    private void displayNumber(int val, LEDColor col) {
        for (int i = 0, mask = 1; i < 7; i++, mask <<= 1) {
            leds[7 - i].setColor(col);
            leds[7 - i].setOn((val & mask) != 0);
        }
    }

    /**
     * Auxiliary routine to scale the brightness of the LED so it is more in 
     * keeping with how people perceive brightness.
     *
     * @param x the raw value to display
     * @param col the maximum LED brightness to use
     * @param perLed the maximum value to display
     * @returns the scaled brightness to actually display
     */
    private int lightValue(int x, int col, int perLed) {
        if (x <= 0 || col <= 0) {
            return 0;
        }
        if (x >= perLed) {
            return col;
        }
        return (x * x * x * col) / (perLed * perLed * perLed);
    }

    /**
     * Display a vU like level in LEDs 1-7
     *
     * @param val the level to display
     * @param max the maximum value expected
     * @param min the minimum value expected
     * @param col the color to display in LEDs
     */
    private void displayLevel(int val, int max, int min, LEDColor col) {
        int LEDS_TO_USE = 7;
        int MAX_LED = 7;
        int range = max - min + 1;
        int perLed = range / LEDS_TO_USE;
        int bucket = (val - min + 1) / perLed;
        int part = (val - min + 1) - bucket * perLed;
        for (int i = 0; i < LEDS_TO_USE; i++) {
            if (bucket > i) {
                leds[MAX_LED - i].setColor(col);
                leds[MAX_LED - i].setOn();
            } else if (bucket == i) {
                leds[MAX_LED - i].setRGB(lightValue(part, col.red(), perLed), lightValue(part, col.green(), perLed), lightValue(part, col.blue(), perLed));
                leds[MAX_LED - i].setOn();
            } else {
                leds[MAX_LED - i].setOff();
            }
        }
    }

    /**
     * Loop waiting for user to press a switch.
     *<p>
     * Since ISwitch.waitForChange() doesn't really block we can loop on both switches ourself.
     *<p>
     * Detect when either switch is pressed by displaying the current value.
     * After 1 second, if it is still pressed start cycling through values every 0.5 seconds.
     * After cycling through 4 new values speed up the cycle time to every 0.3 seconds.
     * When cycle reaches the max value minus one revert to slower cycle speed.
     * Ignore other switch transitions for now.
     *
     */
    private void respondToSwitches() {
        while (true) {
            pause(100);         // check every 0.1 seconds
            int cnt = 0;
            if (sw1.isClosed()) {
                ledsInUse = true;
                displayNumber(channel, getGreen());
                pause(1000);    // wait 1.0 second
                if (sw1.isClosed()) {
                    while (sw1.isClosed()) {
                        channel++;
                        if (channel > 24) {
                            cnt = 0;
                        }
                        if (channel > 26) {
                            channel = 11;
                        }
                        displayNumber(channel, getGreen());
                        cnt++;
                        pause(cnt < 5 ? 500 : 300);    // wait 0.5 second
                    }
                    Spot.getInstance().getRadioPolicyManager().setChannelNumber(channel);
                }
                pause(1000);    // wait 1.0 second
                displayNumber(0, blue);
            }
            if (sw2.isClosed()) {
                cnt = 0;
                ledsInUse = true;
                displayNumber(power, getRed());
                pause(1000);    // wait 1.0 second
                if (sw2.isClosed()) {
                    while (sw2.isClosed()) {
                        power++;
                        if (power > 30) {
                            cnt = 0;
                        }
                        if (power > 32) {
                            power = 0;
                        }
                        displayNumber(power, getRed());
                        cnt++;
                        pause(cnt < 5 ? 500 : 300);    // wait 0.5 second
                    }
                    Spot.getInstance().getRadioPolicyManager().setOutputPower(power - 32);
                }
                pause(1000);    // wait 1.0 second
                displayNumber(0, blue);
            }
            ledsInUse = false;
            checkLightSensor();
        }
    }

    /**
     * Loop to continually transmit packets using current power level & channel setting.
     */
    private void xmitLoop() {
        RadiogramConnection txConn = null;
        xmitDo = true;
        while (xmitDo) {
            try {
                txConn = (RadiogramConnection) Connector.open("radiogram://broadcast:" + BROADCAST_PORT);
                txConn.setMaxBroadcastHops(1);      // don't want packets being rebroadcasted
                currentStrategy.xmitLoop(txConn);
            } catch (IOException) {
                // ignore
            } finally {
                if (txConn != null) {
                    try {
                        txConn.close();
                    } catch (IOException ex) {
                    }
                }
            }
        }
    }

    /**
     * Loop to receive packets and display their RSSI level in the LEDs
     */
    private void recvLoop() {
        RadiogramConnection rcvConn = null;
        recvDo = true;
        while (recvDo) {
            try {
                rcvConn = (RadiogramConnection) Connector.open("radiogram://:" + BROADCAST_PORT);
                currentStrategy.recvLoop(rcvConn);
            } catch (IOException ex) {
                // ignore
            } finally {
                if (rcvConn != null) {
                    try {
                        rcvConn.close();
                    } catch (IOException ex) {
                    }
                }
            }
        }
    }

    /**
     * MIDlet call to start our application.
     */
    protected void startApp() throws MIDletStateChangeException {
        new com.sun.spot.util.BootloaderListener().start();       // Listen for downloads/commands over USB connection
        initialize();
        run();
    }

    /**
     * This will never be called by the Squawk VM.
     */
    protected void pauseApp() {
        // This will never be called by the Squawk VM
    }

    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
    }

    public int getHotaruState() {
        return hotaruState;
    }

    public void setHotaruState(int value) {
        this.hotaruState = value;
        this.updateHotaruState();
    }

    protected void updateHotaruState() {
        if (ledsInUse) {
            return;
        }
        for (int i = 2; i < 8; i++) {
            leds[i].setColor(getBlue());
            leds[i].setOn(hotaruState == 0);
        }
    }

    abstract class Strategy {

        // Fields
        protected ILed greenLed = Spot.getInstance().getGreenLed();
        protected ILed redLed = Spot.getInstance().getRedLed();
        protected int packetPerSecond = 5;
        protected long noPacketsReceivedCount = 0;
        protected boolean isHaltRequested = false;
        protected long selfAddress = Spot.getInstance().getRadioPolicyManager().getIEEEAddress();

        // Abstract methods
        public abstract String getName();

        protected abstract void recv(HotaruWisper packet);

        protected abstract void onRecvTimeout();

        protected abstract HotaruWisper createWisper();

        public abstract LEDColor getStatusColor();

        public void printInfo() {
            log(INFO, this.getName());
            log(INFO, "Packets/s: " + Integer.toString(packetPerSecond));
        }

        public void recvLoop(RadiogramConnection conn) throws IOException {
            conn.setTimeout(xmitInterval() - 5);
            Radiogram rdg = (Radiogram) conn.newDatagram(conn.getMaximumLength());
            while (recvDo && !isHaltRequested) {
                try {
                    rdg.reset();
                    conn.receive(rdg);           // listen for a packet
                    HotaruWisper packet = decodePacket(rdg);
                    if (packet != null) {
                        this.recv(packet);
                    }
                } catch (TimeoutException tex) {        // timeout - display no packet received
                    noPacketsReceivedCount += 1;
                    this.onRecvTimeout();
                }
            }
        }

        public void xmitLoop(RadiogramConnection conn) throws IOException {
            Datagram xdg = conn.newDatagram(conn.getMaximumLength());
            while (xmitDo && !isHaltRequested) {
                greenLed.setOn();
                long nextTime = System.currentTimeMillis() + xmitInterval();
                xdg.reset();
                HotaruWisper wisp = createWisper();
                if (wisp != null) {
                    wisp.writeTo(xdg);
                    conn.send(xdg);
                    this.onXmitComplete(wisp);
                }
                greenLed.setOff();
                long delay = (nextTime - System.currentTimeMillis()) - 2;
                if (delay > 0) {
                    pause(delay);
                }
            }
        }

        public void halt() {
            this.isHaltRequested = true;
        }

        protected long xmitInterval() {
            return 1000 / packetPerSecond;
        }

        protected void onXmitComplete(HotaruWisper packet) {
        }
    }

    class MasterStrategy extends Strategy implements Runnable {

        int blinkIntervalMsec = 1000;
        int state = 0;

        public MasterStrategy(int interval) {
            this.blinkIntervalMsec = interval;
            new Thread(this).start();
        }

        public void run() {
            while (!this.isHaltRequested) {
                this.state = 1 - this.state;
                try {
                    Thread.currentThread().sleep(blinkIntervalMsec);
                } catch (InterruptedException iex) {
                }
            }
        }

        protected void recv(HotaruWisper packet) {
            if (packet.masterAddress > selfAddress) {
                setStrategy(new SlaveStrategy(packet.masterAddress));
            }
        }

        protected HotaruWisper createWisper() {
            HotaruWisper wisp = new HotaruWisper();
            wisp.masterAddress = selfAddress;
            wisp.masterState = this.state;
            wisp.selfAddress = selfAddress;
            return wisp;
        }

        protected void onRecvTimeout() {
        }

        protected void onXmitComplete(HotaruWisper packet) {
            setHotaruState(packet.masterState);
        }

        public void printInfo() {
            super.printInfo();
        }

        public String getName() {
            return "Master";
        }

        public LEDColor getStatusColor() {
            return LEDColor.ORANGE;
        }
    }

    class SlaveStrategy extends Strategy {

        /**
         * Master IEEE Address
         */
        private long syncingMasterAddr;
        private long lastReceiveTime;
        private long slaveTimeout = 3000; // 3sec

        public SlaveStrategy(long syncingMasterIEEEAddress) {
            this.syncingMasterAddr = syncingMasterIEEEAddress;
        }

        public String getName() {
            return "Slave";
        }

        protected void recv(HotaruWisper packet) {
            if (packet.masterAddress > this.syncingMasterAddr) {
                setStrategy(new SlaveStrategy(packet.masterAddress));
            } else if (packet.masterAddress == this.syncingMasterAddr) {
                setHotaruState(packet.masterState);
                this.lastReceiveTime = System.currentTimeMillis();
            }
        }

        protected HotaruWisper createWisper() {
            return null;
        }

        protected void onRecvTimeout() {
            if (System.currentTimeMillis() - lastReceiveTime > slaveTimeout) {
                // Privilege as Master
                setStrategy(new MasterStrategy(BLINK_INTERVAL));
            }
        }

        public void printInfo() {
            super.printInfo();
            log(INFO, "Sync Master :" + IEEEAddress.toDottedHex(syncingMasterAddr));
            log(INFO, "Timeout :" + slaveTimeout + "(ms)");
        }

        public LEDColor getStatusColor() {
            return LEDColor.GREEN;
        }
    }

    // -----------------------------------
    // Packet decoders and encoders.
    // -----------------------------------
    class HotaruWisper {
        public long masterAddress;
        public long selfAddress;
        public int masterState;

        public void writeTo(DataOutput out) throws IOException {
            out.writeByte(PACKET_MAGIC_NUMBER);
            out.writeLong(masterAddress);
            out.writeLong(selfAddress);
            out.writeInt(masterState);
        }

        public String toString() {
            return "Wisper(master: " + IEEEAddress.toDottedHex(masterAddress) +
                    ", sender: " + IEEEAddress.toDottedHex(selfAddress) +
                    ", state: " + masterState +
                    ")";
        }
    }

    private HotaruWisper decodePacket(DataInput in) throws IOException {
        if (in.readByte() != PACKET_MAGIC_NUMBER) {
            return null;
        }
        HotaruWisper wisp = new HotaruWisper();
        wisp.masterAddress = in.readLong();
        wisp.selfAddress = in.readLong();
        wisp.masterState = in.readInt();

        return wisp;
    }
}
