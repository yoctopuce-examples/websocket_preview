import com.yoctopuce.YoctoAPI.YAPIContext;
import com.yoctopuce.YoctoAPI.YAPI_Exception;
import com.yoctopuce.YoctoAPI.YModule;
import com.yoctopuce.YoctoAPI.YRelay;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/wscallback")
public class YServerWebsocket
{

    @OnOpen
    public void onOpen(final Session session)
    {
        // log onOpen for debug purpose
        System.out.println(session.getId() + " has open a connection");
        // since all connection use the same process create a private context
        final YAPIContext yctx = new YAPIContext();
        try {
            yctx.PreregisterHubWebSocketCallback(session);
        } catch (YAPI_Exception e) {
            e.printStackTrace();
            return;
        }

        Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {


                try {
                    // register the YoctoHub/VirtualHub that start the connection
                    yctx.UpdateDeviceList();

                    // list all devices connected on this hub (only for debug propose)
                    System.out.println("Device list:");
                    YModule module = YModule.FirstModuleInContext(yctx);
                    while (module != null) {
                        System.out.println("   " + module.get_serialNumber() + " (" + module.get_productName() + ")");
                        module = module.nextModule();
                    }

                    // play a bit with relay output :-)
                    try {
                        YRelay relay = YRelay.FirstRelayInContext(yctx);
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
                yctx.FreeAPI();
            }
        });
        thread.start();
    }


    @OnClose
    public void onClose(Session session, CloseReason closeReason)
    {
        // log onClose for debug purpose
        System.out.println(session.getId() + " has close a connection");
    }


}
