//import com.yoctopuce.YoctoAPI.YAPIContext;

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
        final String desc = "Connection " + session.getId();
        System.out.println(session.getId() + " has open a connection");

        Thread thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                YAPIContext yctx = new YAPIContext();
                try {
                    yctx.RegisterHubCallback(session);

                    System.out.println("Device list_");
                    YModule module = YModule.FirstModule(yctx);
                    while (module != null) {
                        System.out.println("   " + module.get_serialNumber() + " (" + module.get_productName() + ")");

                        module = module.nextModule();
                    }
                    YRelay relay = YRelay.FirstRelay(yctx);
                    while (relay != null) {
                        relay.set_state(YRelay.STATE_A);
                        relay.set_state(YRelay.STATE_B);
                        relay = relay.nextRelay();
                    }
                } catch (YAPI_Exception ex) {
                    ex.printStackTrace();
                    System.out.println(desc + " error (" + ex.getLocalizedMessage() + ")");
                    return;
                }
                yctx.FreeAPI();
            }
        }

                , " Thread " + desc);
        thread.start();
    }


    @OnClose
    public void onClose(Session session, CloseReason closeReason)
    {
        System.out.println(session.getId() + " has close a connection");
    }

    @OnError
    public void onError(Session session, Throwable throwable)
    {
        System.out.println(session.getId() + " error : " + throwable.getMessage());
        throwable.printStackTrace();
    }

}
