package com.yoctopuce.demo;

import com.yoctopuce.YoctoAPI.*;

/**
 * Hello world!
 */
public class App
{
    public static void main(String[] args)
    {
        try {
            // register the YoctoHub/VirtualHub that start the connection
            YAPI.RegisterHub("ws://localhost");

            // list all devices connected on this hub (only for debug propose)
            System.out.println("Device list:");
            YModule module = YModule.FirstModule();
            while (module != null) {
                System.out.println("   " + module.get_serialNumber() + " (" + module.get_productName() + ")");
                module = module.nextModule();
            }

            // play a bit with relay output :-)
            try {
                YRelay relay = YRelay.FirstRelay();
                if (relay != null) {
                    relay.set_state(YRelay.STATE_A);
                    Thread.sleep(500);
                    relay.set_state(YRelay.STATE_B);
                    Thread.sleep(250);
                    relay.set_state(YRelay.STATE_A);
                    Thread.sleep(250);
                    relay.set_state(YRelay.STATE_B);
                    Thread.sleep(500);
                    relay.set_state(YRelay.STATE_A);
                    Thread.sleep(1000);
                    relay.set_state(YRelay.STATE_B);
                    Thread.sleep(500);
                    relay.set_state(YRelay.STATE_A);
                    Thread.sleep(1000);
                    relay.set_state(YRelay.STATE_B);
                } else {
                    System.out.println("No Relay connected");
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        } catch (YAPI_Exception ex) {
            System.out.println(" error (" + ex.getLocalizedMessage() + ")");
            ex.printStackTrace();
        }
        // no not forget to FreeAPI to ensure that all pending operation
        // are finished and freed
        YAPI.FreeAPI();
    }
}
